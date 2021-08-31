/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define ATRACE_TAG ATRACE_TAG_RESOURCES

#include "androidfw/AssetManager2.h"

#include <algorithm>
#include <iterator>
#include <map>
#include <set>

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "androidfw/ResourceUtils.h"
#include "androidfw/Util.h"
#include "utils/ByteOrder.h"
#include "utils/Trace.h"

#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#endif

namespace android {

struct FindEntryResult {
  // A pointer to the value of the resource table entry.
  std::variant<Res_value, const ResTable_map_entry*> entry;

  // The configuration for which the resulting entry was defined. This is already swapped to host
  // endianness.
  ResTable_config config;

  // The bitmask of configuration axis with which the resource value varies.
  uint32_t type_flags;

  // The dynamic package ID map for the package from which this resource came from.
  const DynamicRefTable* dynamic_ref_table;

  // The package name of the resource.
  const std::string* package_name;

  // The string pool reference to the type's name. This uses a different string pool than
  // the global string pool, but this is hidden from the caller.
  StringPoolRef type_string_ref;

  // The string pool reference to the entry's name. This uses a different string pool than
  // the global string pool, but this is hidden from the caller.
  StringPoolRef entry_string_ref;
};

AssetManager2::AssetManager2() {
  memset(&configuration_, 0, sizeof(configuration_));
}

bool AssetManager2::SetApkAssets(const std::vector<const ApkAssets*>& apk_assets,
                                 bool invalidate_caches, bool filter_incompatible_configs) {
  apk_assets_ = apk_assets;
  BuildDynamicRefTable();
  RebuildFilterList(filter_incompatible_configs);
  if (invalidate_caches) {
    InvalidateCaches(static_cast<uint32_t>(-1));
  }
  return true;
}

void AssetManager2::BuildDynamicRefTable() {
  package_groups_.clear();
  package_ids_.fill(0xff);

  // A mapping from apk assets path to the runtime package id of its first loaded package.
  std::unordered_map<std::string, uint8_t> apk_assets_package_ids;

  // Overlay resources are not directly referenced by an application so their resource ids
  // can change throughout the application's lifetime. Assign overlay package ids last.
  std::vector<const ApkAssets*> sorted_apk_assets(apk_assets_);
  std::stable_partition(sorted_apk_assets.begin(), sorted_apk_assets.end(), [](const ApkAssets* a) {
    return !a->IsOverlay();
  });

  // The assets cookie must map to the position of the apk assets in the unsorted apk assets list.
  std::unordered_map<const ApkAssets*, ApkAssetsCookie> apk_assets_cookies;
  apk_assets_cookies.reserve(apk_assets_.size());
  for (size_t i = 0, n = apk_assets_.size(); i < n; i++) {
    apk_assets_cookies[apk_assets_[i]] = static_cast<ApkAssetsCookie>(i);
  }

  // 0x01 is reserved for the android package.
  int next_package_id = 0x02;
  for (const ApkAssets* apk_assets : sorted_apk_assets) {
    const LoadedArsc* loaded_arsc = apk_assets->GetLoadedArsc();
    for (const std::unique_ptr<const LoadedPackage>& package : loaded_arsc->GetPackages()) {
      // Get the package ID or assign one if a shared library.
      int package_id;
      if (package->IsDynamic()) {
        package_id = next_package_id++;
      } else {
        package_id = package->GetPackageId();
      }

      // Add the mapping for package ID to index if not present.
      uint8_t idx = package_ids_[package_id];
      if (idx == 0xff) {
        package_ids_[package_id] = idx = static_cast<uint8_t>(package_groups_.size());
        package_groups_.push_back({});

        if (apk_assets->IsOverlay()) {
          // The target package must precede the overlay package in the apk assets paths in order
          // to take effect.
          const auto& loaded_idmap = apk_assets->GetLoadedIdmap();
          auto target_package_iter = apk_assets_package_ids.find(loaded_idmap->TargetApkPath());
          if (target_package_iter == apk_assets_package_ids.end()) {
             LOG(INFO) << "failed to find target package for overlay "
                       << loaded_idmap->OverlayApkPath();
          } else {
            const uint8_t target_package_id = target_package_iter->second;
            const uint8_t target_idx = package_ids_[target_package_id];
            CHECK(target_idx != 0xff) << "overlay added to apk_assets_package_ids but does not"
                                      << " have an assigned package group";

            PackageGroup& target_package_group = package_groups_[target_idx];

            // Create a special dynamic reference table for the overlay to rewrite references to
            // overlay resources as references to the target resources they overlay.
            auto overlay_table = std::make_shared<OverlayDynamicRefTable>(
                loaded_idmap->GetOverlayDynamicRefTable(target_package_id));
            package_groups_.back().dynamic_ref_table = overlay_table;

            // Add the overlay resource map to the target package's set of overlays.
            target_package_group.overlays_.push_back(
                ConfiguredOverlay{loaded_idmap->GetTargetResourcesMap(target_package_id,
                                                                      overlay_table.get()),
                                  apk_assets_cookies[apk_assets]});
          }
        }

        DynamicRefTable* ref_table = package_groups_.back().dynamic_ref_table.get();
        ref_table->mAssignedPackageId = package_id;
        ref_table->mAppAsLib = package->IsDynamic() && package->GetPackageId() == 0x7f;
      }
      PackageGroup* package_group = &package_groups_[idx];

      // Add the package and to the set of packages with the same ID.
      package_group->packages_.push_back(ConfiguredPackage{package.get(), {}});
      package_group->cookies_.push_back(apk_assets_cookies[apk_assets]);

      // Add the package name -> build time ID mappings.
      for (const DynamicPackageEntry& entry : package->GetDynamicPackageMap()) {
        String16 package_name(entry.package_name.c_str(), entry.package_name.size());
        package_group->dynamic_ref_table->mEntries.replaceValueFor(
            package_name, static_cast<uint8_t>(entry.package_id));
      }

      apk_assets_package_ids.insert(std::make_pair(apk_assets->GetPath(), package_id));
    }
  }

  // Now assign the runtime IDs so that we have a build-time to runtime ID map.
  const auto package_groups_end = package_groups_.end();
  for (auto iter = package_groups_.begin(); iter != package_groups_end; ++iter) {
    const std::string& package_name = iter->packages_[0].loaded_package_->GetPackageName();
    for (auto iter2 = package_groups_.begin(); iter2 != package_groups_end; ++iter2) {
      iter2->dynamic_ref_table->addMapping(String16(package_name.c_str(), package_name.size()),
                                           iter->dynamic_ref_table->mAssignedPackageId);
    }
  }
}

void AssetManager2::DumpToLog() const {
  base::ScopedLogSeverity _log(base::INFO);

  LOG(INFO) << base::StringPrintf("AssetManager2(this=%p)", this);

  std::string list;
  for (const auto& apk_assets : apk_assets_) {
    base::StringAppendF(&list, "%s,", apk_assets->GetPath().c_str());
  }
  LOG(INFO) << "ApkAssets: " << list;

  list = "";
  for (size_t i = 0; i < package_ids_.size(); i++) {
    if (package_ids_[i] != 0xff) {
      base::StringAppendF(&list, "%02x -> %d, ", (int)i, package_ids_[i]);
    }
  }
  LOG(INFO) << "Package ID map: " << list;

  for (const auto& package_group: package_groups_) {
    list = "";
    for (const auto& package : package_group.packages_) {
      const LoadedPackage* loaded_package = package.loaded_package_;
      base::StringAppendF(&list, "%s(%02x%s), ", loaded_package->GetPackageName().c_str(),
                          loaded_package->GetPackageId(),
                          (loaded_package->IsDynamic() ? " dynamic" : ""));
    }
    LOG(INFO) << base::StringPrintf("PG (%02x): ",
                                    package_group.dynamic_ref_table->mAssignedPackageId)
              << list;

    for (size_t i = 0; i < 256; i++) {
      if (package_group.dynamic_ref_table->mLookupTable[i] != 0) {
        LOG(INFO) << base::StringPrintf("    e[0x%02x] -> 0x%02x", (uint8_t) i,
                                        package_group.dynamic_ref_table->mLookupTable[i]);
      }
    }
  }
}

const ResStringPool* AssetManager2::GetStringPoolForCookie(ApkAssetsCookie cookie) const {
  if (cookie < 0 || static_cast<size_t>(cookie) >= apk_assets_.size()) {
    return nullptr;
  }
  return apk_assets_[cookie]->GetLoadedArsc()->GetStringPool();
}

const DynamicRefTable* AssetManager2::GetDynamicRefTableForPackage(uint32_t package_id) const {
  if (package_id >= package_ids_.size()) {
    return nullptr;
  }

  const size_t idx = package_ids_[package_id];
  if (idx == 0xff) {
    return nullptr;
  }
  return package_groups_[idx].dynamic_ref_table.get();
}

std::shared_ptr<const DynamicRefTable> AssetManager2::GetDynamicRefTableForCookie(
    ApkAssetsCookie cookie) const {
  for (const PackageGroup& package_group : package_groups_) {
    for (const ApkAssetsCookie& package_cookie : package_group.cookies_) {
      if (package_cookie == cookie) {
        return package_group.dynamic_ref_table;
      }
    }
  }
  return nullptr;
}

const std::unordered_map<std::string, std::string>*
  AssetManager2::GetOverlayableMapForPackage(uint32_t package_id) const {

  if (package_id >= package_ids_.size()) {
    return nullptr;
  }

  const size_t idx = package_ids_[package_id];
  if (idx == 0xff) {
    return nullptr;
  }

  const PackageGroup& package_group = package_groups_[idx];
  if (package_group.packages_.size() == 0) {
    return nullptr;
  }

  const auto loaded_package = package_group.packages_[0].loaded_package_;
  return &loaded_package->GetOverlayableMap();
}

bool AssetManager2::GetOverlayablesToString(const android::StringPiece& package_name,
                                            std::string* out) const {
  uint8_t package_id = 0U;
  for (const auto& apk_assets : apk_assets_) {
    const LoadedArsc* loaded_arsc = apk_assets->GetLoadedArsc();
    if (loaded_arsc == nullptr) {
      continue;
    }

    const auto& loaded_packages = loaded_arsc->GetPackages();
    if (loaded_packages.empty()) {
      continue;
    }

    const auto& loaded_package = loaded_packages[0];
    if (loaded_package->GetPackageName() == package_name) {
      package_id = GetAssignedPackageId(loaded_package.get());
      break;
    }
  }

  if (package_id == 0U) {
    ANDROID_LOG(ERROR) << base::StringPrintf("No package with name '%s", package_name.data());
    return false;
  }

  const size_t idx = package_ids_[package_id];
  if (idx == 0xff) {
    return false;
  }

  std::string output;
  for (const ConfiguredPackage& package : package_groups_[idx].packages_) {
    const LoadedPackage* loaded_package = package.loaded_package_;
    for (auto it = loaded_package->begin(); it != loaded_package->end(); it++) {
      const OverlayableInfo* info = loaded_package->GetOverlayableInfo(*it);
      if (info != nullptr) {
        ResourceName res_name;
        if (!GetResourceName(*it, &res_name)) {
          ANDROID_LOG(ERROR) << base::StringPrintf(
              "Unable to retrieve name of overlayable resource 0x%08x", *it);
          return false;
        }

        const std::string name = ToFormattedResourceString(&res_name);
        output.append(base::StringPrintf(
            "resource='%s' overlayable='%s' actor='%s' policy='0x%08x'\n",
            name.c_str(), info->name.c_str(), info->actor.c_str(), info->policy_flags));
      }
    }
  }

  *out = std::move(output);
  return true;
}

bool AssetManager2::ContainsAllocatedTable() const {
  return std::find_if(apk_assets_.begin(), apk_assets_.end(),
                      std::mem_fn(&ApkAssets::IsTableAllocated)) != apk_assets_.end();
}

void AssetManager2::SetConfiguration(const ResTable_config& configuration) {
  const int diff = configuration_.diff(configuration);
  configuration_ = configuration;

  if (diff) {
    RebuildFilterList();
    InvalidateCaches(static_cast<uint32_t>(diff));
  }
}

std::set<std::string> AssetManager2::GetNonSystemOverlayPaths() const {
  std::set<std::string> non_system_overlays;
  for (const PackageGroup& package_group : package_groups_) {
    bool found_system_package = false;
    for (const ConfiguredPackage& package : package_group.packages_) {
      if (package.loaded_package_->IsSystem()) {
        found_system_package = true;
        break;
      }
    }

    if (!found_system_package) {
      for (const ConfiguredOverlay& overlay : package_group.overlays_) {
        non_system_overlays.insert(apk_assets_[overlay.cookie]->GetPath());
      }
    }
  }

  return non_system_overlays;
}

std::set<ResTable_config> AssetManager2::GetResourceConfigurations(bool exclude_system,
                                                                   bool exclude_mipmap) const {
  ATRACE_NAME("AssetManager::GetResourceConfigurations");
  const auto non_system_overlays =
      (exclude_system) ? GetNonSystemOverlayPaths() : std::set<std::string>();

  std::set<ResTable_config> configurations;
  for (const PackageGroup& package_group : package_groups_) {
    for (size_t i = 0; i < package_group.packages_.size(); i++) {
      const ConfiguredPackage& package = package_group.packages_[i];
      if (exclude_system && package.loaded_package_->IsSystem()) {
        continue;
      }

      auto apk_assets = apk_assets_[package_group.cookies_[i]];
      if (exclude_system && apk_assets->IsOverlay()
          && non_system_overlays.find(apk_assets->GetPath()) == non_system_overlays.end()) {
        // Exclude overlays that target system resources.
        continue;
      }

      package.loaded_package_->CollectConfigurations(exclude_mipmap, &configurations);
    }
  }
  return configurations;
}

std::set<std::string> AssetManager2::GetResourceLocales(bool exclude_system,
                                                        bool merge_equivalent_languages) const {
  ATRACE_NAME("AssetManager::GetResourceLocales");
  std::set<std::string> locales;
  const auto non_system_overlays =
      (exclude_system) ? GetNonSystemOverlayPaths() : std::set<std::string>();

  for (const PackageGroup& package_group : package_groups_) {
    for (size_t i = 0; i < package_group.packages_.size(); i++) {
      const ConfiguredPackage& package = package_group.packages_[i];
      if (exclude_system && package.loaded_package_->IsSystem()) {
        continue;
      }

      auto apk_assets = apk_assets_[package_group.cookies_[i]];
      if (exclude_system && apk_assets->IsOverlay()
          && non_system_overlays.find(apk_assets->GetPath()) == non_system_overlays.end()) {
        // Exclude overlays that target system resources.
        continue;
      }

      package.loaded_package_->CollectLocales(merge_equivalent_languages, &locales);
    }
  }
  return locales;
}

std::unique_ptr<Asset> AssetManager2::Open(const std::string& filename,
                                           Asset::AccessMode mode) const {
  const std::string new_path = "assets/" + filename;
  return OpenNonAsset(new_path, mode);
}

std::unique_ptr<Asset> AssetManager2::Open(const std::string& filename, ApkAssetsCookie cookie,
                                           Asset::AccessMode mode) const {
  const std::string new_path = "assets/" + filename;
  return OpenNonAsset(new_path, cookie, mode);
}

std::unique_ptr<AssetDir> AssetManager2::OpenDir(const std::string& dirname) const {
  ATRACE_NAME("AssetManager::OpenDir");

  std::string full_path = "assets/" + dirname;
  std::unique_ptr<SortedVector<AssetDir::FileInfo>> files =
      util::make_unique<SortedVector<AssetDir::FileInfo>>();

  // Start from the back.
  for (auto iter = apk_assets_.rbegin(); iter != apk_assets_.rend(); ++iter) {
    const ApkAssets* apk_assets = *iter;
    if (apk_assets->IsOverlay()) {
      continue;
    }

    auto func = [&](const StringPiece& name, FileType type) {
      AssetDir::FileInfo info;
      info.setFileName(String8(name.data(), name.size()));
      info.setFileType(type);
      info.setSourceName(String8(apk_assets->GetPath().c_str()));
      files->add(info);
    };

    if (!apk_assets->GetAssetsProvider()->ForEachFile(full_path, func)) {
      return {};
    }
  }

  std::unique_ptr<AssetDir> asset_dir = util::make_unique<AssetDir>();
  asset_dir->setFileList(files.release());
  return asset_dir;
}

// Search in reverse because that's how we used to do it and we need to preserve behaviour.
// This is unfortunate, because ClassLoaders delegate to the parent first, so the order
// is inconsistent for split APKs.
std::unique_ptr<Asset> AssetManager2::OpenNonAsset(const std::string& filename,
                                                   Asset::AccessMode mode,
                                                   ApkAssetsCookie* out_cookie) const {
  for (int32_t i = apk_assets_.size() - 1; i >= 0; i--) {
    // Prevent RRO from modifying assets and other entries accessed by file
    // path. Explicitly asking for a path in a given package (denoted by a
    // cookie) is still OK.
    if (apk_assets_[i]->IsOverlay()) {
      continue;
    }

    std::unique_ptr<Asset> asset = apk_assets_[i]->GetAssetsProvider()->Open(filename, mode);
    if (asset) {
      if (out_cookie != nullptr) {
        *out_cookie = i;
      }
      return asset;
    }
  }

  if (out_cookie != nullptr) {
    *out_cookie = kInvalidCookie;
  }
  return {};
}

std::unique_ptr<Asset> AssetManager2::OpenNonAsset(const std::string& filename,
                                                   ApkAssetsCookie cookie,
                                                   Asset::AccessMode mode) const {
  if (cookie < 0 || static_cast<size_t>(cookie) >= apk_assets_.size()) {
    return {};
  }
  return apk_assets_[cookie]->GetAssetsProvider()->Open(filename, mode);
}

ApkAssetsCookie AssetManager2::FindEntry(uint32_t resid, uint16_t density_override,
                                         bool /*stop_at_first_match*/,
                                         bool ignore_configuration,
                                         FindEntryResult* out_entry) const {
  if (resource_resolution_logging_enabled_) {
    // Clear the last logged resource resolution.
    ResetResourceResolution();
    last_resolution_.resid = resid;
  }

  // Might use this if density_override != 0.
  ResTable_config density_override_config;

  // Select our configuration or generate a density override configuration.
  const ResTable_config* desired_config = &configuration_;
  if (density_override != 0 && density_override != configuration_.density) {
    density_override_config = configuration_;
    density_override_config.density = density_override;
    desired_config = &density_override_config;
  }

  // Retrieve the package group from the package id of the resource id.
  if (!is_valid_resid(resid)) {
    LOG(ERROR) << base::StringPrintf("Invalid ID 0x%08x.", resid);
    return kInvalidCookie;
  }

  const uint32_t package_id = get_package_id(resid);
  const uint8_t type_idx = get_type_id(resid) - 1;
  const uint16_t entry_idx = get_entry_id(resid);
  uint8_t package_idx = package_ids_[package_id];
  if (package_idx == 0xff) {
    ANDROID_LOG(ERROR) << base::StringPrintf("No package ID %02x found for ID 0x%08x.",
                                             package_id, resid);
    return kInvalidCookie;
  }

  const PackageGroup& package_group = package_groups_[package_idx];
  ApkAssetsCookie cookie = FindEntryInternal(package_group, type_idx, entry_idx, *desired_config,
                                             false /* stop_at_first_match */,
                                             ignore_configuration, out_entry);
  if (UNLIKELY(cookie == kInvalidCookie)) {
    return kInvalidCookie;
  }

  if (!apk_assets_[cookie]->IsLoader()) {
    for (const auto& id_map : package_group.overlays_) {
      auto overlay_entry = id_map.overlay_res_maps_.Lookup(resid);
      if (!overlay_entry) {
        // No id map entry exists for this target resource.
        continue;
      } else if (overlay_entry.IsInlineValue()) {
        // The target resource is overlaid by an inline value not represented by a resource.
        out_entry->entry = overlay_entry.GetInlineValue();
        out_entry->dynamic_ref_table = id_map.overlay_res_maps_.GetOverlayDynamicRefTable();
        cookie = id_map.cookie;
        continue;
      }

      FindEntryResult overlay_result;
      ApkAssetsCookie overlay_cookie = FindEntry(overlay_entry.GetResourceId(), density_override,
                                                 false /* stop_at_first_match */,
                                                 ignore_configuration, &overlay_result);
      if (UNLIKELY(overlay_cookie == kInvalidCookie)) {
        continue;
      }

      if (!overlay_result.config.isBetterThan(out_entry->config, desired_config)
          && overlay_result.config.compare(out_entry->config) != 0) {
        // The configuration of the entry for the overlay must be equal to or better than the target
        // configuration to be chosen as the better value.
        continue;
      }

      cookie = overlay_cookie;
      out_entry->entry = overlay_result.entry;
      out_entry->config = overlay_result.config;
      out_entry->dynamic_ref_table = id_map.overlay_res_maps_.GetOverlayDynamicRefTable();
      if (resource_resolution_logging_enabled_) {
        last_resolution_.steps.push_back(
            Resolution::Step{Resolution::Step::Type::OVERLAID, overlay_result.config.toString(),
                             overlay_result.package_name});
      }
    }
  }

  if (resource_resolution_logging_enabled_) {
    last_resolution_.cookie = cookie;
    last_resolution_.type_string_ref = out_entry->type_string_ref;
    last_resolution_.entry_string_ref = out_entry->entry_string_ref;
  }

  return cookie;
}

ApkAssetsCookie AssetManager2::FindEntryInternal(const PackageGroup& package_group,
                                                 uint8_t type_idx, uint16_t entry_idx,
                                                 const ResTable_config& desired_config,
                                                 bool /*stop_at_first_match*/,
                                                 bool ignore_configuration,
                                                 FindEntryResult* out_entry) const {
  ApkAssetsCookie best_cookie = kInvalidCookie;
  const LoadedPackage* best_package = nullptr;
  const ResTable_type* best_type = nullptr;
  const ResTable_config* best_config = nullptr;
  ResTable_config best_config_copy;
  uint32_t best_offset = 0u;
  uint32_t type_flags = 0u;

  Resolution::Step::Type resolution_type = Resolution::Step::Type::NO_ENTRY;
  std::vector<Resolution::Step> resolution_steps;

  // If desired_config is the same as the set configuration, then we can use our filtered list
  // and we don't need to match the configurations, since they already matched.
  const bool use_fast_path = !ignore_configuration && &desired_config == &configuration_;

  const size_t package_count = package_group.packages_.size();
  for (size_t pi = 0; pi < package_count; pi++) {
    const ConfiguredPackage& loaded_package_impl = package_group.packages_[pi];
    const LoadedPackage* loaded_package = loaded_package_impl.loaded_package_;
    ApkAssetsCookie cookie = package_group.cookies_[pi];

    // If the type IDs are offset in this package, we need to take that into account when searching
    // for a type.
    const TypeSpec* type_spec = loaded_package->GetTypeSpecByTypeIndex(type_idx);
    if (UNLIKELY(type_spec == nullptr)) {
      continue;
    }

    // If the package is an overlay or custom loader,
    // then even configurations that are the same MUST be chosen.
    const bool package_is_loader = loaded_package->IsCustomLoader();
    type_flags |= type_spec->GetFlagsForEntryIndex(entry_idx);

    if (use_fast_path) {
      const FilteredConfigGroup& filtered_group = loaded_package_impl.filtered_configs_[type_idx];
      const std::vector<ResTable_config>& candidate_configs = filtered_group.configurations;
      const size_t type_count = candidate_configs.size();
      for (uint32_t i = 0; i < type_count; i++) {
        const ResTable_config& this_config = candidate_configs[i];

        // We can skip calling ResTable_config::match() because we know that all candidate
        // configurations that do NOT match have been filtered-out.
        if (best_config == nullptr) {
          resolution_type = Resolution::Step::Type::INITIAL;
        } else if (this_config.isBetterThan(*best_config, &desired_config)) {
          resolution_type = (package_is_loader) ? Resolution::Step::Type::BETTER_MATCH_LOADER
                                                : Resolution::Step::Type::BETTER_MATCH;
        } else if (package_is_loader && this_config.compare(*best_config) == 0) {
          resolution_type = Resolution::Step::Type::OVERLAID_LOADER;
        } else {
          if (resource_resolution_logging_enabled_) {
            resolution_type = (package_is_loader) ? Resolution::Step::Type::SKIPPED_LOADER
                                                  : Resolution::Step::Type::SKIPPED;
            resolution_steps.push_back(Resolution::Step{resolution_type,
                                                        this_config.toString(),
                                                        &loaded_package->GetPackageName()});
          }
          continue;
        }

        // The configuration matches and is better than the previous selection.
        // Find the entry value if it exists for this configuration.
        const ResTable_type* type = filtered_group.types[i];
        const uint32_t offset = LoadedPackage::GetEntryOffset(type, entry_idx);
        if (offset == ResTable_type::NO_ENTRY) {
          if (resource_resolution_logging_enabled_) {
            if (package_is_loader) {
              resolution_type = Resolution::Step::Type::NO_ENTRY_LOADER;
            } else {
              resolution_type = Resolution::Step::Type::NO_ENTRY;
            }
            resolution_steps.push_back(Resolution::Step{resolution_type,
                                                        this_config.toString(),
                                                        &loaded_package->GetPackageName()});
          }
          continue;
        }

        best_cookie = cookie;
        best_package = loaded_package;
        best_type = type;
        best_config = &this_config;
        best_offset = offset;

        if (resource_resolution_logging_enabled_) {
          last_resolution_.steps.push_back(Resolution::Step{resolution_type,
                                                            this_config.toString(),
                                                            &loaded_package->GetPackageName()});
        }
      }
    } else {
      // This is the slower path, which doesn't use the filtered list of configurations.
      // Here we must read the ResTable_config from the mmapped APK, convert it to host endianness
      // and fill in any new fields that did not exist when the APK was compiled.
      // Furthermore when selecting configurations we can't just record the pointer to the
      // ResTable_config, we must copy it.
      const auto iter_end = type_spec->types + type_spec->type_count;
      for (auto iter = type_spec->types; iter != iter_end; ++iter) {
        ResTable_config this_config{};

        if (!ignore_configuration) {
          this_config.copyFromDtoH((*iter)->config);
          if (!this_config.match(desired_config)) {
            continue;
          }

          if (best_config == nullptr) {
            resolution_type = Resolution::Step::Type::INITIAL;
          } else if (this_config.isBetterThan(*best_config, &desired_config)) {
            resolution_type = (package_is_loader) ? Resolution::Step::Type::BETTER_MATCH_LOADER
                                                  : Resolution::Step::Type::BETTER_MATCH;
          } else if (package_is_loader && this_config.compare(*best_config) == 0) {
            resolution_type = Resolution::Step::Type::OVERLAID_LOADER;
          } else {
            continue;
          }
        }

        // The configuration matches and is better than the previous selection.
        // Find the entry value if it exists for this configuration.
        const uint32_t offset = LoadedPackage::GetEntryOffset(*iter, entry_idx);
        if (offset == ResTable_type::NO_ENTRY) {
          continue;
        }

        best_cookie = cookie;
        best_package = loaded_package;
        best_type = *iter;
        best_config_copy = this_config;
        best_config = &best_config_copy;
        best_offset = offset;

        if (ignore_configuration) {
          // Any configuration will suffice, so break.
          break;
        }

        if (resource_resolution_logging_enabled_) {
          last_resolution_.steps.push_back(Resolution::Step{resolution_type,
                                                            this_config.toString(),
                                                            &loaded_package->GetPackageName()});
        }
      }
    }
  }

  if (UNLIKELY(best_cookie == kInvalidCookie)) {
    return kInvalidCookie;
  }

  const ResTable_entry* best_entry = LoadedPackage::GetEntryFromOffset(best_type, best_offset);
  if (UNLIKELY(best_entry == nullptr)) {
    return kInvalidCookie;
  }

  const uint16_t entry_size = dtohs(best_entry->size);
  if (entry_size >= sizeof(ResTable_map_entry) &&
      (dtohs(best_entry->flags) & ResTable_entry::FLAG_COMPLEX)) {
    // The entry represents a bag/map.
    out_entry->entry = reinterpret_cast<const ResTable_map_entry*>(best_entry);
  } else {
    // The entry represents a value.
    Res_value value;
    value.copyFrom_dtoh(*reinterpret_cast<const Res_value*>(
        reinterpret_cast<const uint8_t*>(best_entry) + entry_size));
    out_entry->entry = value;
  }

  out_entry->config = *best_config;
  out_entry->type_flags = type_flags;
  out_entry->package_name = &best_package->GetPackageName();
  out_entry->type_string_ref = StringPoolRef(best_package->GetTypeStringPool(), best_type->id - 1);
  out_entry->entry_string_ref =
          StringPoolRef(best_package->GetKeyStringPool(), best_entry->key.index);
  out_entry->dynamic_ref_table = package_group.dynamic_ref_table.get();

  return best_cookie;
}

void AssetManager2::ResetResourceResolution() const {
  last_resolution_.cookie = kInvalidCookie;
  last_resolution_.resid = 0;
  last_resolution_.steps.clear();
  last_resolution_.type_string_ref = StringPoolRef();
  last_resolution_.entry_string_ref = StringPoolRef();
}

void AssetManager2::SetResourceResolutionLoggingEnabled(bool enabled) {
  resource_resolution_logging_enabled_ = enabled;
  if (!enabled) {
    ResetResourceResolution();
  }
}

std::string AssetManager2::GetLastResourceResolution() const {
  if (!resource_resolution_logging_enabled_) {
    LOG(ERROR) << "Must enable resource resolution logging before getting path.";
    return std::string();
  }

  auto cookie = last_resolution_.cookie;
  if (cookie == kInvalidCookie) {
    LOG(ERROR) << "AssetManager hasn't resolved a resource to read resolution path.";
    return std::string();
  }

  uint32_t resid = last_resolution_.resid;
  std::vector<Resolution::Step>& steps = last_resolution_.steps;

  ResourceName resource_name;
  std::string resource_name_string;

  const LoadedPackage* package =
          apk_assets_[cookie]->GetLoadedArsc()->GetPackageById(get_package_id(resid));

  if (package != nullptr) {
    ToResourceName(last_resolution_.type_string_ref,
                   last_resolution_.entry_string_ref,
                   package->GetPackageName(),
                   &resource_name);
    resource_name_string = ToFormattedResourceString(&resource_name);
  }

  std::stringstream log_stream;
  log_stream << base::StringPrintf("Resolution for 0x%08x ", resid)
            << resource_name_string
            << "\n\tFor config -"
            << configuration_.toString();

  std::string prefix;
  for (Resolution::Step step : steps) {
    switch (step.type) {
      case Resolution::Step::Type::INITIAL:
        prefix = "Found initial";
        break;
      case Resolution::Step::Type::BETTER_MATCH:
        prefix = "Found better";
        break;
      case Resolution::Step::Type::BETTER_MATCH_LOADER:
        prefix = "Found better in loader";
        break;
      case Resolution::Step::Type::OVERLAID:
        prefix = "Overlaid";
        break;
      case Resolution::Step::Type::OVERLAID_LOADER:
        prefix = "Overlaid by loader";
        break;
      case Resolution::Step::Type::SKIPPED:
        prefix = "Skipped";
        break;
      case Resolution::Step::Type::SKIPPED_LOADER:
        prefix = "Skipped loader";
        break;
      case Resolution::Step::Type::NO_ENTRY:
        prefix = "No entry";
        break;
      case Resolution::Step::Type::NO_ENTRY_LOADER:
        prefix = "No entry for loader";
        break;
    }

    if (!prefix.empty()) {
      log_stream << "\n\t" << prefix << ": " << *step.package_name;

      if (!step.config_name.isEmpty()) {
        log_stream << " -" << step.config_name;
      }
    }
  }

  return log_stream.str();
}

bool AssetManager2::GetResourceName(uint32_t resid, ResourceName* out_name) const {
  FindEntryResult entry;
  ApkAssetsCookie cookie = FindEntry(resid, 0u /* density_override */,
                                     true /* stop_at_first_match */,
                                     true /* ignore_configuration */, &entry);
  if (cookie == kInvalidCookie) {
    return false;
  }

  return ToResourceName(entry.type_string_ref,
                        entry.entry_string_ref,
                        *entry.package_name,
                        out_name);
}

bool AssetManager2::GetResourceFlags(uint32_t resid, uint32_t* out_flags) const {
  FindEntryResult entry;
  ApkAssetsCookie cookie = FindEntry(resid, 0u /* density_override */,
                                     false /* stop_at_first_match */,
                                     true /* ignore_configuration */, &entry);
  if (cookie != kInvalidCookie) {
    *out_flags = entry.type_flags;
    return true;
  }
  return false;
}

ApkAssetsCookie AssetManager2::GetResource(uint32_t resid, bool may_be_bag,
                                           uint16_t density_override, Res_value* out_value,
                                           ResTable_config* out_selected_config,
                                           uint32_t* out_flags) const {
  FindEntryResult entry;
  ApkAssetsCookie cookie = FindEntry(resid, density_override, false /* stop_at_first_match */,
                                     false /* ignore_configuration */, &entry);
  if (cookie == kInvalidCookie) {
    return kInvalidCookie;
  }

  auto result_map_entry = std::get_if<const ResTable_map_entry*>(&entry.entry);
  if (result_map_entry != nullptr) {
    if (!may_be_bag) {
      LOG(ERROR) << base::StringPrintf("Resource %08x is a complex map type.", resid);
      return kInvalidCookie;
    }

    // Create a reference since we can't represent this complex type as a Res_value.
    out_value->dataType = Res_value::TYPE_REFERENCE;
    out_value->data = resid;
    *out_selected_config = entry.config;
    *out_flags = entry.type_flags;
    return cookie;
  }

  // Convert the package ID to the runtime assigned package ID.
  *out_value = std::get<Res_value>(entry.entry);
  entry.dynamic_ref_table->lookupResourceValue(out_value);

  *out_selected_config = entry.config;
  *out_flags = entry.type_flags;
  return cookie;
}

ApkAssetsCookie AssetManager2::ResolveReference(ApkAssetsCookie cookie, Res_value* in_out_value,
                                                ResTable_config* in_out_selected_config,
                                                uint32_t* in_out_flags,
                                                uint32_t* out_last_reference) const {
  constexpr const int kMaxIterations = 20;

  for (size_t iteration = 0u; in_out_value->dataType == Res_value::TYPE_REFERENCE &&
                              in_out_value->data != 0u && iteration < kMaxIterations;
       iteration++) {
    *out_last_reference = in_out_value->data;
    uint32_t new_flags = 0u;
    cookie = GetResource(in_out_value->data, true /*may_be_bag*/, 0u /*density_override*/,
                         in_out_value, in_out_selected_config, &new_flags);
    if (cookie == kInvalidCookie) {
      return kInvalidCookie;
    }
    if (in_out_flags != nullptr) {
      *in_out_flags |= new_flags;
    }
    if (*out_last_reference == in_out_value->data) {
      // This reference can't be resolved, so exit now and let the caller deal with it.
      return cookie;
    }
  }
  return cookie;
}

const std::vector<uint32_t> AssetManager2::GetBagResIdStack(uint32_t resid) {
  auto cached_iter = cached_bag_resid_stacks_.find(resid);
  if (cached_iter != cached_bag_resid_stacks_.end()) {
    return cached_iter->second;
  } else {
    auto found_resids = std::vector<uint32_t>();
    GetBag(resid, found_resids);
    // Cache style stacks if they are not already cached.
    cached_bag_resid_stacks_[resid] = found_resids;
    return found_resids;
  }
}

const ResolvedBag* AssetManager2::GetBag(uint32_t resid) {
  auto found_resids = std::vector<uint32_t>();
  auto bag = GetBag(resid, found_resids);

  // Cache style stacks if they are not already cached.
  auto cached_iter = cached_bag_resid_stacks_.find(resid);
  if (cached_iter == cached_bag_resid_stacks_.end()) {
    cached_bag_resid_stacks_[resid] = found_resids;
  }
  return bag;
}

static bool compare_bag_entries(const ResolvedBag::Entry& entry1,
    const ResolvedBag::Entry& entry2) {
  return entry1.key < entry2.key;
}

const ResolvedBag* AssetManager2::GetBag(uint32_t resid, std::vector<uint32_t>& child_resids) {
  auto cached_iter = cached_bags_.find(resid);
  if (cached_iter != cached_bags_.end()) {
    return cached_iter->second.get();
  }

  FindEntryResult entry;
  ApkAssetsCookie cookie = FindEntry(resid, 0u /* density_override */,
                                     false /* stop_at_first_match */,
                                     false /* ignore_configuration */,
                                     &entry);
  if (cookie == kInvalidCookie) {
    return nullptr;
  }

  auto result_map_entry = std::get_if<const ResTable_map_entry*>(&entry.entry);
  if (result_map_entry == nullptr) {
    // Not a bag, nothing to do.
    return nullptr;
  }

  auto map = reinterpret_cast<const ResTable_map_entry*>(*result_map_entry);
  auto map_entry = reinterpret_cast<const ResTable_map*>(
      reinterpret_cast<const uint8_t*>(map) + map->size);
  const ResTable_map* const map_entry_end = map_entry + dtohl(map->count);

  // Keep track of ids that have already been seen to prevent infinite loops caused by circular
  // dependencies between bags
  child_resids.push_back(resid);

  uint32_t parent_resid = dtohl(map->parent.ident);
  if (parent_resid == 0U || std::find(child_resids.begin(), child_resids.end(), parent_resid)
      != child_resids.end()) {
    // There is no parent or a circular dependency exist, meaning there is nothing to inherit and
    // we can do a simple copy of the entries in the map.
    const size_t entry_count = map_entry_end - map_entry;
    util::unique_cptr<ResolvedBag> new_bag{reinterpret_cast<ResolvedBag*>(
        malloc(sizeof(ResolvedBag) + (entry_count * sizeof(ResolvedBag::Entry))))};

    bool sort_entries = false;
    ResolvedBag::Entry* new_entry = new_bag->entries;
    for (; map_entry != map_entry_end; ++map_entry) {
      uint32_t new_key = dtohl(map_entry->name.ident);
      if (!is_internal_resid(new_key)) {
        // Attributes, arrays, etc don't have a resource id as the name. They specify
        // other data, which would be wrong to change via a lookup.
        if (entry.dynamic_ref_table->lookupResourceId(&new_key) != NO_ERROR) {
          LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", new_key,
                                           resid);
          return nullptr;
        }
      }
      new_entry->cookie = cookie;
      new_entry->key = new_key;
      new_entry->key_pool = nullptr;
      new_entry->type_pool = nullptr;
      new_entry->style = resid;
      new_entry->value.copyFrom_dtoh(map_entry->value);
      status_t err = entry.dynamic_ref_table->lookupResourceValue(&new_entry->value);
      if (err != NO_ERROR) {
        LOG(ERROR) << base::StringPrintf(
            "Failed to resolve value t=0x%02x d=0x%08x for key 0x%08x.", new_entry->value.dataType,
            new_entry->value.data, new_key);
        return nullptr;
      }
      sort_entries = sort_entries ||
          (new_entry != new_bag->entries && (new_entry->key < (new_entry - 1U)->key));
      ++new_entry;
    }

    if (sort_entries) {
      std::sort(new_bag->entries, new_bag->entries + entry_count, compare_bag_entries);
    }

    new_bag->type_spec_flags = entry.type_flags;
    new_bag->entry_count = static_cast<uint32_t>(entry_count);
    ResolvedBag* result = new_bag.get();
    cached_bags_[resid] = std::move(new_bag);
    return result;
  }

  // In case the parent is a dynamic reference, resolve it.
  entry.dynamic_ref_table->lookupResourceId(&parent_resid);

  // Get the parent and do a merge of the keys.
  const ResolvedBag* parent_bag = GetBag(parent_resid, child_resids);
  if (parent_bag == nullptr) {
    // Failed to get the parent that should exist.
    LOG(ERROR) << base::StringPrintf("Failed to find parent 0x%08x of bag 0x%08x.", parent_resid,
                                     resid);
    return nullptr;
  }

  // Create the max possible entries we can make. Once we construct the bag,
  // we will realloc to fit to size.
  const size_t max_count = parent_bag->entry_count + dtohl(map->count);
  util::unique_cptr<ResolvedBag> new_bag{reinterpret_cast<ResolvedBag*>(
      malloc(sizeof(ResolvedBag) + (max_count * sizeof(ResolvedBag::Entry))))};
  ResolvedBag::Entry* new_entry = new_bag->entries;

  const ResolvedBag::Entry* parent_entry = parent_bag->entries;
  const ResolvedBag::Entry* const parent_entry_end = parent_entry + parent_bag->entry_count;

  // The keys are expected to be in sorted order. Merge the two bags.
  bool sort_entries = false;
  while (map_entry != map_entry_end && parent_entry != parent_entry_end) {
    uint32_t child_key = dtohl(map_entry->name.ident);
    if (!is_internal_resid(child_key)) {
      if (entry.dynamic_ref_table->lookupResourceId(&child_key) != NO_ERROR) {
        LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", child_key,
                                         resid);
        return nullptr;
      }
    }

    if (child_key <= parent_entry->key) {
      // Use the child key if it comes before the parent
      // or is equal to the parent (overrides).
      new_entry->cookie = cookie;
      new_entry->key = child_key;
      new_entry->key_pool = nullptr;
      new_entry->type_pool = nullptr;
      new_entry->value.copyFrom_dtoh(map_entry->value);
      new_entry->style = resid;
      status_t err = entry.dynamic_ref_table->lookupResourceValue(&new_entry->value);
      if (err != NO_ERROR) {
        LOG(ERROR) << base::StringPrintf(
            "Failed to resolve value t=0x%02x d=0x%08x for key 0x%08x.", new_entry->value.dataType,
            new_entry->value.data, child_key);
        return nullptr;
      }
      ++map_entry;
    } else {
      // Take the parent entry as-is.
      memcpy(new_entry, parent_entry, sizeof(*new_entry));
    }

    sort_entries = sort_entries ||
        (new_entry != new_bag->entries && (new_entry->key < (new_entry - 1U)->key));
    if (child_key >= parent_entry->key) {
      // Move to the next parent entry if we used it or it was overridden.
      ++parent_entry;
    }
    // Increment to the next entry to fill.
    ++new_entry;
  }

  // Finish the child entries if they exist.
  while (map_entry != map_entry_end) {
    uint32_t new_key = dtohl(map_entry->name.ident);
    if (!is_internal_resid(new_key)) {
      if (entry.dynamic_ref_table->lookupResourceId(&new_key) != NO_ERROR) {
        LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", new_key,
                                         resid);
        return nullptr;
      }
    }
    new_entry->cookie = cookie;
    new_entry->key = new_key;
    new_entry->key_pool = nullptr;
    new_entry->type_pool = nullptr;
    new_entry->value.copyFrom_dtoh(map_entry->value);
    new_entry->style = resid;
    status_t err = entry.dynamic_ref_table->lookupResourceValue(&new_entry->value);
    if (err != NO_ERROR) {
      LOG(ERROR) << base::StringPrintf("Failed to resolve value t=0x%02x d=0x%08x for key 0x%08x.",
                                       new_entry->value.dataType, new_entry->value.data, new_key);
      return nullptr;
    }
    sort_entries = sort_entries ||
        (new_entry != new_bag->entries && (new_entry->key < (new_entry - 1U)->key));
    ++map_entry;
    ++new_entry;
  }

  // Finish the parent entries if they exist.
  if (parent_entry != parent_entry_end) {
    // Take the rest of the parent entries as-is.
    const size_t num_entries_to_copy = parent_entry_end - parent_entry;
    memcpy(new_entry, parent_entry, num_entries_to_copy * sizeof(*new_entry));
    new_entry += num_entries_to_copy;
  }

  // Resize the resulting array to fit.
  const size_t actual_count = new_entry - new_bag->entries;
  if (actual_count != max_count) {
    new_bag.reset(reinterpret_cast<ResolvedBag*>(realloc(
        new_bag.release(), sizeof(ResolvedBag) + (actual_count * sizeof(ResolvedBag::Entry)))));
  }

  if (sort_entries) {
    std::sort(new_bag->entries, new_bag->entries + actual_count, compare_bag_entries);
  }

  // Combine flags from the parent and our own bag.
  new_bag->type_spec_flags = entry.type_flags | parent_bag->type_spec_flags;
  new_bag->entry_count = static_cast<uint32_t>(actual_count);
  ResolvedBag* result = new_bag.get();
  cached_bags_[resid] = std::move(new_bag);
  return result;
}

static bool Utf8ToUtf16(const StringPiece& str, std::u16string* out) {
  ssize_t len =
      utf8_to_utf16_length(reinterpret_cast<const uint8_t*>(str.data()), str.size(), false);
  if (len < 0) {
    return false;
  }
  out->resize(static_cast<size_t>(len));
  utf8_to_utf16(reinterpret_cast<const uint8_t*>(str.data()), str.size(), &*out->begin(),
                static_cast<size_t>(len + 1));
  return true;
}

uint32_t AssetManager2::GetResourceId(const std::string& resource_name,
                                      const std::string& fallback_type,
                                      const std::string& fallback_package) const {
  StringPiece package_name, type, entry;
  if (!ExtractResourceName(resource_name, &package_name, &type, &entry)) {
    return 0u;
  }

  if (entry.empty()) {
    return 0u;
  }

  if (package_name.empty()) {
    package_name = fallback_package;
  }

  if (type.empty()) {
    type = fallback_type;
  }

  std::u16string type16;
  if (!Utf8ToUtf16(type, &type16)) {
    return 0u;
  }

  std::u16string entry16;
  if (!Utf8ToUtf16(entry, &entry16)) {
    return 0u;
  }

  const StringPiece16 kAttr16 = u"attr";
  const static std::u16string kAttrPrivate16 = u"^attr-private";

  for (const PackageGroup& package_group : package_groups_) {
    for (const ConfiguredPackage& package_impl : package_group.packages_) {
      const LoadedPackage* package = package_impl.loaded_package_;
      if (package_name != package->GetPackageName()) {
        // All packages in the same group are expected to have the same package name.
        break;
      }

      uint32_t resid = package->FindEntryByName(type16, entry16);
      if (resid == 0u && kAttr16 == type16) {
        // Private attributes in libraries (such as the framework) are sometimes encoded
        // under the type '^attr-private' in order to leave the ID space of public 'attr'
        // free for future additions. Check '^attr-private' for the same name.
        resid = package->FindEntryByName(kAttrPrivate16, entry16);
      }

      if (resid != 0u) {
        return fix_package_id(resid, package_group.dynamic_ref_table->mAssignedPackageId);
      }
    }
  }
  return 0u;
}

void AssetManager2::RebuildFilterList(bool filter_incompatible_configs) {
  for (PackageGroup& group : package_groups_) {
    for (ConfiguredPackage& impl : group.packages_) {
      // Destroy it.
      impl.filtered_configs_.~ByteBucketArray();

      // Re-create it.
      new (&impl.filtered_configs_) ByteBucketArray<FilteredConfigGroup>();

      // Create the filters here.
      impl.loaded_package_->ForEachTypeSpec([&](const TypeSpec* spec, uint8_t type_index) {
        FilteredConfigGroup& group = impl.filtered_configs_.editItemAt(type_index);
        const auto iter_end = spec->types + spec->type_count;
        for (auto iter = spec->types; iter != iter_end; ++iter) {
          ResTable_config this_config;
          this_config.copyFromDtoH((*iter)->config);
          if (!filter_incompatible_configs || this_config.match(configuration_)) {
            group.configurations.push_back(this_config);
            group.types.push_back(*iter);
          }
        }
      });
    }
  }
}

void AssetManager2::InvalidateCaches(uint32_t diff) {
  cached_bag_resid_stacks_.clear();

  if (diff == 0xffffffffu) {
    // Everything must go.
    cached_bags_.clear();
    return;
  }

  // Be more conservative with what gets purged. Only if the bag has other possible
  // variations with respect to what changed (diff) should we remove it.
  for (auto iter = cached_bags_.cbegin(); iter != cached_bags_.cend();) {
    if (diff & iter->second->type_spec_flags) {
      iter = cached_bags_.erase(iter);
    } else {
      ++iter;
    }
  }
}

uint8_t AssetManager2::GetAssignedPackageId(const LoadedPackage* package) const {
  for (auto& package_group : package_groups_) {
    for (auto& package2 : package_group.packages_) {
      if (package2.loaded_package_ == package) {
        return package_group.dynamic_ref_table->mAssignedPackageId;
      }
    }
  }
  return 0;
}

std::unique_ptr<Theme> AssetManager2::NewTheme() {
  return std::unique_ptr<Theme>(new Theme(this));
}

Theme::Theme(AssetManager2* asset_manager) : asset_manager_(asset_manager) {
}

Theme::~Theme() = default;

namespace {

struct ThemeEntry {
  ApkAssetsCookie cookie;
  uint32_t type_spec_flags;
  Res_value value;
};

struct ThemeType {
  int entry_count;
  ThemeEntry entries[0];
};

constexpr size_t kTypeCount = std::numeric_limits<uint8_t>::max() + 1;

}  // namespace

struct Theme::Package {
  // Each element of Type will be a dynamically sized object
  // allocated to have the entries stored contiguously with the Type.
  std::array<util::unique_cptr<ThemeType>, kTypeCount> types;
};

bool Theme::ApplyStyle(uint32_t resid, bool force) {
  ATRACE_NAME("Theme::ApplyStyle");

  const ResolvedBag* bag = asset_manager_->GetBag(resid);
  if (bag == nullptr) {
    return false;
  }

  // Merge the flags from this style.
  type_spec_flags_ |= bag->type_spec_flags;

  int last_type_idx = -1;
  int last_package_idx = -1;
  Package* last_package = nullptr;
  ThemeType* last_type = nullptr;

  // Iterate backwards, because each bag is sorted in ascending key ID order, meaning we will only
  // need to perform one resize per type.
  using reverse_bag_iterator = std::reverse_iterator<const ResolvedBag::Entry*>;
  const auto bag_iter_end = reverse_bag_iterator(begin(bag));
  for (auto bag_iter = reverse_bag_iterator(end(bag)); bag_iter != bag_iter_end; ++bag_iter) {
    const uint32_t attr_resid = bag_iter->key;

    // If the resource ID passed in is not a style, the key can be some other identifier that is not
    // a resource ID. We should fail fast instead of operating with strange resource IDs.
    if (!is_valid_resid(attr_resid)) {
      return false;
    }

    // We don't use the 0-based index for the type so that we can avoid doing ID validation
    // upon lookup. Instead, we keep space for the type ID 0 in our data structures. Since
    // the construction of this type is guarded with a resource ID check, it will never be
    // populated, and querying type ID 0 will always fail.
    const int package_idx = get_package_id(attr_resid);
    const int type_idx = get_type_id(attr_resid);
    const int entry_idx = get_entry_id(attr_resid);

    if (last_package_idx != package_idx) {
      std::unique_ptr<Package>& package = packages_[package_idx];
      if (package == nullptr) {
        package.reset(new Package());
      }
      last_package_idx = package_idx;
      last_package = package.get();
      last_type_idx = -1;
    }

    if (last_type_idx != type_idx) {
      util::unique_cptr<ThemeType>& type = last_package->types[type_idx];
      if (type == nullptr) {
        // Allocate enough memory to contain this entry_idx. Since we're iterating in reverse over
        // a sorted list of attributes, this shouldn't be resized again during this method call.
        type.reset(reinterpret_cast<ThemeType*>(
            calloc(sizeof(ThemeType) + (entry_idx + 1) * sizeof(ThemeEntry), 1)));
        type->entry_count = entry_idx + 1;
      } else if (entry_idx >= type->entry_count) {
        // Reallocate the memory to contain this entry_idx. Since we're iterating in reverse over
        // a sorted list of attributes, this shouldn't be resized again during this method call.
        const int new_count = entry_idx + 1;
        type.reset(reinterpret_cast<ThemeType*>(
            realloc(type.release(), sizeof(ThemeType) + (new_count * sizeof(ThemeEntry)))));

        // Clear out the newly allocated space (which isn't zeroed).
        memset(type->entries + type->entry_count, 0,
               (new_count - type->entry_count) * sizeof(ThemeEntry));
        type->entry_count = new_count;
      }
      last_type_idx = type_idx;
      last_type = type.get();
    }

    ThemeEntry& entry = last_type->entries[entry_idx];
    if (force || (entry.value.dataType == Res_value::TYPE_NULL &&
                  entry.value.data != Res_value::DATA_NULL_EMPTY)) {
      entry.cookie = bag_iter->cookie;
      entry.type_spec_flags |= bag->type_spec_flags;
      entry.value = bag_iter->value;
    }
  }
  return true;
}

ApkAssetsCookie Theme::GetAttribute(uint32_t resid, Res_value* out_value,
                                    uint32_t* out_flags) const {
  int cnt = 20;

  uint32_t type_spec_flags = 0u;

  do {
    const int package_idx = get_package_id(resid);
    const Package* package = packages_[package_idx].get();
    if (package != nullptr) {
      // The themes are constructed with a 1-based type ID, so no need to decrement here.
      const int type_idx = get_type_id(resid);
      const ThemeType* type = package->types[type_idx].get();
      if (type != nullptr) {
        const int entry_idx = get_entry_id(resid);
        if (entry_idx < type->entry_count) {
          const ThemeEntry& entry = type->entries[entry_idx];
          type_spec_flags |= entry.type_spec_flags;

          if (entry.value.dataType == Res_value::TYPE_ATTRIBUTE) {
            if (cnt > 0) {
              cnt--;
              resid = entry.value.data;
              continue;
            }
            return kInvalidCookie;
          }

          // @null is different than @empty.
          if (entry.value.dataType == Res_value::TYPE_NULL &&
              entry.value.data != Res_value::DATA_NULL_EMPTY) {
            return kInvalidCookie;
          }

          *out_value = entry.value;
          *out_flags = type_spec_flags;
          return entry.cookie;
        }
      }
    }
    break;
  } while (true);
  return kInvalidCookie;
}

ApkAssetsCookie Theme::ResolveAttributeReference(ApkAssetsCookie cookie, Res_value* in_out_value,
                                                 ResTable_config* in_out_selected_config,
                                                 uint32_t* in_out_type_spec_flags,
                                                 uint32_t* out_last_ref) const {
  if (in_out_value->dataType == Res_value::TYPE_ATTRIBUTE) {
    uint32_t new_flags;
    cookie = GetAttribute(in_out_value->data, in_out_value, &new_flags);
    if (cookie == kInvalidCookie) {
      return kInvalidCookie;
    }

    if (in_out_type_spec_flags != nullptr) {
      *in_out_type_spec_flags |= new_flags;
    }
  }
  return asset_manager_->ResolveReference(cookie, in_out_value, in_out_selected_config,
                                          in_out_type_spec_flags, out_last_ref);
}

void Theme::Clear() {
  type_spec_flags_ = 0u;
  for (std::unique_ptr<Package>& package : packages_) {
    package.reset();
  }
}

void Theme::SetTo(const Theme& o) {
  if (this == &o) {
    return;
  }

  type_spec_flags_ = o.type_spec_flags_;

  if (asset_manager_ == o.asset_manager_) {
    // The theme comes from the same asset manager so all theme data can be copied exactly
    for (size_t p = 0; p < packages_.size(); p++) {
      const Package *package = o.packages_[p].get();
      if (package == nullptr) {
        // The other theme doesn't have this package, clear ours.
        packages_[p].reset();
        continue;
      }

      if (packages_[p] == nullptr) {
        // The other theme has this package, but we don't. Make one.
        packages_[p].reset(new Package());
      }

      for (size_t t = 0; t < package->types.size(); t++) {
        const ThemeType *type = package->types[t].get();
        if (type == nullptr) {
          // The other theme doesn't have this type, clear ours.
          packages_[p]->types[t].reset();
          continue;
        }

        // Create a new type and update it to theirs.
        const size_t type_alloc_size = sizeof(ThemeType) + (type->entry_count * sizeof(ThemeEntry));
        void *copied_data = malloc(type_alloc_size);
        memcpy(copied_data, type, type_alloc_size);
        packages_[p]->types[t].reset(reinterpret_cast<ThemeType *>(copied_data));
      }
    }
  } else {
    std::map<ApkAssetsCookie, ApkAssetsCookie> src_to_dest_asset_cookies;
    typedef std::map<int, int> SourceToDestinationRuntimePackageMap;
    std::map<ApkAssetsCookie, SourceToDestinationRuntimePackageMap> src_asset_cookie_id_map;

    // Determine which ApkAssets are loaded in both theme AssetManagers.
    std::vector<const ApkAssets*> src_assets = o.asset_manager_->GetApkAssets();
    for (size_t i = 0; i < src_assets.size(); i++) {
      const ApkAssets* src_asset = src_assets[i];

      std::vector<const ApkAssets*> dest_assets = asset_manager_->GetApkAssets();
      for (size_t j = 0; j < dest_assets.size(); j++) {
        const ApkAssets* dest_asset = dest_assets[j];

        // Map the runtime package of the source apk asset to the destination apk asset.
        if (src_asset->GetPath() == dest_asset->GetPath()) {
          const std::vector<std::unique_ptr<const LoadedPackage>>& src_packages =
              src_asset->GetLoadedArsc()->GetPackages();
          const std::vector<std::unique_ptr<const LoadedPackage>>& dest_packages =
              dest_asset->GetLoadedArsc()->GetPackages();

          SourceToDestinationRuntimePackageMap package_map;

          // The source and destination package should have the same number of packages loaded in
          // the same order.
          const size_t N = src_packages.size();
          CHECK(N == dest_packages.size())
              << " LoadedArsc " << src_asset->GetPath() << " differs number of packages.";
          for (size_t p = 0; p < N; p++) {
            auto& src_package = src_packages[p];
            auto& dest_package = dest_packages[p];
            CHECK(src_package->GetPackageName() == dest_package->GetPackageName())
                << " Package " << src_package->GetPackageName() << " differs in load order.";

            int src_package_id = o.asset_manager_->GetAssignedPackageId(src_package.get());
            int dest_package_id = asset_manager_->GetAssignedPackageId(dest_package.get());
            package_map[src_package_id] = dest_package_id;
          }

          src_to_dest_asset_cookies.insert(std::make_pair(i, j));
          src_asset_cookie_id_map.insert(std::make_pair(i, package_map));
          break;
        }
      }
    }

    // Reset the data in the destination theme.
    for (size_t p = 0; p < packages_.size(); p++) {
      if (packages_[p] != nullptr) {
        packages_[p].reset();
      }
    }

    for (size_t p = 0; p < packages_.size(); p++) {
      const Package *package = o.packages_[p].get();
      if (package == nullptr) {
        continue;
      }

      for (size_t t = 0; t < package->types.size(); t++) {
        const ThemeType *type = package->types[t].get();
        if (type == nullptr) {
          continue;
        }

        for (size_t e = 0; e < type->entry_count; e++) {
          const ThemeEntry &entry = type->entries[e];
          if (entry.value.dataType == Res_value::TYPE_NULL &&
              entry.value.data != Res_value::DATA_NULL_EMPTY) {
            continue;
          }

          bool is_reference = (entry.value.dataType == Res_value::TYPE_ATTRIBUTE
                               || entry.value.dataType == Res_value::TYPE_REFERENCE
                               || entry.value.dataType == Res_value::TYPE_DYNAMIC_ATTRIBUTE
                               || entry.value.dataType == Res_value::TYPE_DYNAMIC_REFERENCE)
                              && entry.value.data != 0x0;

          // If the attribute value represents an attribute or reference, the package id of the
          // value needs to be rewritten to the package id of the value in the destination.
          uint32_t attribute_data = entry.value.data;
          if (is_reference) {
            // Determine the package id of the reference in the destination AssetManager.
            auto value_package_map = src_asset_cookie_id_map.find(entry.cookie);
            if (value_package_map == src_asset_cookie_id_map.end()) {
              continue;
            }

            auto value_dest_package = value_package_map->second.find(
                get_package_id(entry.value.data));
            if (value_dest_package == value_package_map->second.end()) {
              continue;
            }

            attribute_data = fix_package_id(entry.value.data, value_dest_package->second);
          }

          // Find the cookie of the value in the destination. If the source apk is not loaded in the
          // destination, only copy resources that do not reference resources in the source.
          ApkAssetsCookie data_dest_cookie;
          auto value_dest_cookie = src_to_dest_asset_cookies.find(entry.cookie);
          if (value_dest_cookie != src_to_dest_asset_cookies.end()) {
            data_dest_cookie = value_dest_cookie->second;
          } else {
            if (is_reference || entry.value.dataType == Res_value::TYPE_STRING) {
              continue;
            } else {
              data_dest_cookie = 0x0;
            }
          }

          // The package id of the attribute needs to be rewritten to the package id of the
          // attribute in the destination.
          int attribute_dest_package_id = p;
          if (attribute_dest_package_id != 0x01) {
            // Find the cookie of the attribute resource id in the source AssetManager
            FindEntryResult attribute_entry_result;
            ApkAssetsCookie attribute_cookie =
                o.asset_manager_->FindEntry(make_resid(p, t, e), 0 /* density_override */ ,
                                            true /* stop_at_first_match */,
                                            true /* ignore_configuration */,
                                            &attribute_entry_result);

            // Determine the package id of the attribute in the destination AssetManager.
            auto attribute_package_map = src_asset_cookie_id_map.find(attribute_cookie);
            if (attribute_package_map == src_asset_cookie_id_map.end()) {
              continue;
            }
            auto attribute_dest_package = attribute_package_map->second.find(
                attribute_dest_package_id);
            if (attribute_dest_package == attribute_package_map->second.end()) {
              continue;
            }
            attribute_dest_package_id = attribute_dest_package->second;
          }

          // Lazily instantiate the destination package.
          std::unique_ptr<Package>& dest_package = packages_[attribute_dest_package_id];
          if (dest_package == nullptr) {
            dest_package.reset(new Package());
          }

          // Lazily instantiate and resize the destination type.
          util::unique_cptr<ThemeType>& dest_type = dest_package->types[t];
          if (dest_type == nullptr || dest_type->entry_count < type->entry_count) {
            const size_t type_alloc_size = sizeof(ThemeType)
                + (type->entry_count * sizeof(ThemeEntry));
            void* dest_data = malloc(type_alloc_size);
            memset(dest_data, 0, type->entry_count * sizeof(ThemeEntry));

            // Copy the existing destination type values if the type is resized.
            if (dest_type != nullptr) {
              memcpy(dest_data, type, sizeof(ThemeType)
                                      + (dest_type->entry_count * sizeof(ThemeEntry)));
            }

            dest_type.reset(reinterpret_cast<ThemeType *>(dest_data));
            dest_type->entry_count = type->entry_count;
          }

          dest_type->entries[e].cookie = data_dest_cookie;
          dest_type->entries[e].value.dataType = entry.value.dataType;
          dest_type->entries[e].value.data = attribute_data;
          dest_type->entries[e].type_spec_flags = entry.type_spec_flags;
        }
      }
    }
  }
}

void Theme::Dump() const {
  base::ScopedLogSeverity _log(base::INFO);
  LOG(INFO) << base::StringPrintf("Theme(this=%p, AssetManager2=%p)", this, asset_manager_);

  for (int p = 0; p < packages_.size(); p++) {
    auto& package = packages_[p];
    if (package == nullptr) {
      continue;
    }

    for (int t = 0; t < package->types.size(); t++) {
      auto& type = package->types[t];
      if (type == nullptr) {
        continue;
      }

      for (int e = 0; e < type->entry_count; e++) {
        auto& entry = type->entries[e];
        if (entry.value.dataType == Res_value::TYPE_NULL &&
            entry.value.data != Res_value::DATA_NULL_EMPTY) {
          continue;
        }

        LOG(INFO) << base::StringPrintf("  entry(0x%08x)=(0x%08x) type=(0x%02x), cookie(%d)",
                                        make_resid(p, t, e), entry.value.data,
                                        entry.value.dataType, entry.cookie);
      }
    }
  }
}

}  // namespace android
