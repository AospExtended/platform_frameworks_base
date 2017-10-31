/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.substratum;

import android.os.FileUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class IOUtils {
    public static final String SYSTEM_THEME_PATH = "/data/system/theme";
    public static final String SYSTEM_THEME_CACHE_PATH = SYSTEM_THEME_PATH + File.separator
            + "cache";
    public static final String SYSTEM_THEME_FONT_PATH = SYSTEM_THEME_PATH + File.separator
            + "fonts";
    public static final String SYSTEM_THEME_AUDIO_PATH = SYSTEM_THEME_PATH + File.separator
            + "audio";
    public static final String SYSTEM_THEME_RINGTONE_PATH = SYSTEM_THEME_AUDIO_PATH
            + File.separator + "ringtones";
    public static final String SYSTEM_THEME_NOTIFICATION_PATH = SYSTEM_THEME_AUDIO_PATH
            + File.separator + "notifications";
    public static final String SYSTEM_THEME_ALARM_PATH = SYSTEM_THEME_AUDIO_PATH
            + File.separator + "alarms";
    public static final String SYSTEM_THEME_UI_SOUNDS_PATH = SYSTEM_THEME_AUDIO_PATH
            + File.separator + "ui";
    public static final String SYSTEM_THEME_BOOTANIMATION_PATH = SYSTEM_THEME_PATH + File.separator
            + "bootanimation.zip";
    private static final String TAG = "SubstratumService";

    private static boolean dirExists(String dirPath) {
        final File dir = new File(dirPath);

        return dir.exists() && dir.isDirectory();
    }

    public static void createDirIfNotExists(String dirPath) {
        if (dirExists(dirPath)) {
            return;
        }

        File dir = new File(dirPath);
        if (dir.mkdir()) {
            setPermissions(dir, FileUtils.S_IRWXU | FileUtils.S_IRWXG |
                    FileUtils.S_IROTH | FileUtils.S_IXOTH);
        }
    }

    public static void createThemeDirIfNotExists() {
        createDirIfNotExists(SYSTEM_THEME_PATH);
    }

    public static void createThemeCacheDirIfNotExists() {
        createDirIfNotExists(SYSTEM_THEME_CACHE_PATH);
    }

    public static void createFontDirIfNotExists() {
        createDirIfNotExists(SYSTEM_THEME_FONT_PATH);
    }

    public static void createAudioDirIfNotExists() {
        createDirIfNotExists(SYSTEM_THEME_AUDIO_PATH);
    }

    public static void deleteThemedFonts() {
        try {
            deleteRecursive(new File(SYSTEM_THEME_FONT_PATH));
        } catch (Exception e) {
            Log.e(TAG, "", e);
        }
    }

    public static void deleteThemedAudio() {
        try {
            deleteRecursive(new File(SYSTEM_THEME_AUDIO_PATH));
        } catch (Exception e) {
            Log.e(TAG, "", e);
        }
    }

    public static void clearThemeCache() {
        try {
            deleteRecursive(new File(SYSTEM_THEME_CACHE_PATH));
            createThemeCacheDirIfNotExists();
        } catch (Exception e) {
            Log.e(TAG, "", e);
        }
    }

    public static void copyFolder(File source, File dest) {
        if (!dest.exists()) {
            boolean created = dest.mkdirs();
            if (!created) {
                Log.e(TAG, "Could not create destination folder...");
            }
        }

        File[] files = source.listFiles();
        for (File file : files) {
            try {
                File newFile = new File(dest.getAbsolutePath() + File.separator +
                        file.getName());
                if (file.isFile()) {
                    bufferedCopy(file, newFile);
                } else {
                    copyFolder(file, newFile);
                }
            } catch (Exception e) {
                Log.e(TAG, "", e);
            }
        }
    }

    public static void copyFolder(String source, String dest) {
        copyFolder(new File(source), new File(dest));
    }

    public static void unzip(String source, String destination) {
        try (ZipInputStream inputStream = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(source)))) {
            ZipEntry zipEntry;
            int count;
            byte[] buffer = new byte[8192];

            while ((zipEntry = inputStream.getNextEntry()) != null) {
                File file = new File(destination, zipEntry.getName());
                File dir = zipEntry.isDirectory() ? file : file.getParentFile();

                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new FileNotFoundException("Failed to ensure directory: " +
                            dir.getAbsolutePath());
                }

                if (zipEntry.isDirectory()) {
                    continue;
                }

                try (FileOutputStream outputStream = new FileOutputStream(file)) {
                    while ((count = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, count);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "", e);
        }
    }

    public static void bufferedCopy(String source, String dest) {
        try {
            bufferedCopy(new FileInputStream(source), new FileOutputStream(dest));
        } catch (Exception e) {
            Log.e(TAG, "", e);
        }
    }

    public static void bufferedCopy(File source, File dest) {
        try {
            bufferedCopy(new FileInputStream(source), new FileOutputStream(dest));
        } catch (Exception e) {
            Log.e(TAG, "", e);
        }
    }

    public static void bufferedCopy(InputStream source, OutputStream dest) {
        try {
            BufferedInputStream in = new BufferedInputStream(source);
            BufferedOutputStream out = new BufferedOutputStream(dest);
            byte[] buff = new byte[32 * 1024];
            int len;

            // Let's bulletproof this a bit
            while ((len = in.read(buff)) != -1) {
                out.write(buff, 0, len);
            }

            in.close();
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "", e);
        }
    }

    public static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }

        boolean deleted = fileOrDirectory.delete();
        if (!deleted) {
            Log.e(TAG, "Could not delete file or directory - \'" +
                    fileOrDirectory.getName() + "\'");
        }
    }

    public static void setPermissions(File path, int permissions) {
        FileUtils.setPermissions(path, permissions, -1, -1);
    }

    public static void setPermissionsRecursive(File dir, int file, int folder) {
        if (!dir.isDirectory()) {
            setPermissions(dir, file);
            return;
        }

        for (File child : dir.listFiles()) {
            if (child.isDirectory()) {
                setPermissionsRecursive(child, file, folder);
                setPermissions(child, folder);
            } else {
                setPermissions(child, file);
            }
        }

        setPermissions(dir, folder);
    }
}

