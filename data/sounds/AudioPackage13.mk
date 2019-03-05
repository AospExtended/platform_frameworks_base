#
# Audio Package 13 - L
#
# Include this file in a product makefile to include these audio files
#
#

LOCAL_PATH := frameworks/base/data/sounds

# Simple files that do not require renaming
ALARM_FILES := Argon Carbon Helium Krypton Neon Oxygen Osmium Platinum Timer
NOTIFICATION_FILES := Ariel Ceres Carme Elara Europa Iapetus Io Rhea Salacia Titan Tethys
RINGTONE_FILES := Atria Callisto Dione Ganymede Luna Oberon Phobos Pyxis Sedna Titania Triton \
	Umbriel
EFFECT_FILES := Effect_Tick KeypressReturn KeypressInvalid KeypressDelete KeypressSpacebar KeypressStandard \
	camera_focus Dock Undock Lock Unlock Trusted ChargingStarted InCallNotification
MATERIAL_EFFECT_FILES := camera_click VideoRecord WirelessChargingStarted LowBattery VideoStop

PRODUCT_COPY_FILES += $(foreach fn,$(ALARM_FILES),\
	$(LOCAL_PATH)/alarms/material/ogg/$(fn).ogg:system/media/audio/alarms/$(fn).ogg)

PRODUCT_COPY_FILES += $(foreach fn,$(NOTIFICATION_FILES),\
	$(LOCAL_PATH)/notifications/material/ogg/$(fn).ogg:system/media/audio/notifications/$(fn).ogg)

PRODUCT_COPY_FILES += $(foreach fn,$(RINGTONE_FILES),\
	$(LOCAL_PATH)/ringtones/material/ogg/$(fn).ogg:system/media/audio/ringtones/$(fn).ogg)

PRODUCT_COPY_FILES += $(foreach fn,$(EFFECT_FILES),\
	$(LOCAL_PATH)/effects/ogg/$(fn).ogg:system/media/audio/ui/$(fn).ogg)
PRODUCT_COPY_FILES += $(foreach fn,$(MATERIAL_EFFECT_FILES),\
	$(LOCAL_PATH)/effects/material/ogg/$(fn).ogg:system/media/audio/ui/$(fn).ogg)
