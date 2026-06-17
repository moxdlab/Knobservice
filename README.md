# Knobservice for Android Automotive 13

This Service enables the custom-made rotary knob to control the Android Automotive operating system. The Rotary Knob was provided by moxdlab as part of this study.

## Prerequisites
- Raspberry Pi 4B (4GB RAM)
- Micro SD Card (32GB or more)
- Linux OS with least 300GB of free space

note: multiple 64 GB SD cards were used for this project

## Setting up Android Automotive
To get the AOSP Sourcecode and the specific libraries for Raspberry Pi, use following commands:
```bash
# Get the AOSP source code
repo init -u https://android.googlesource.com/platform/manifest -b android-13.0.0_r75 --depth=1

mkdir -p .repo/local_manifests && \
curl -o .repo/local_manifests/manifest_brcm_rpi4.xml https://raw.githubusercontent.com/moxdlab/Knobservice/DPAD/manifest_brcm_rpi4.xml && \
curl -o .repo/local_manifests/remove_projects.xml https://raw.githubusercontent.com/moxdlab/Knobservice/DPAD/remove_projects.xml

repo sync
````
Depending on your internet connection, the download process may take several hours.



## Setting up The Knobservice
To implement the KnobService, use the following commands:
```text
git clone -b DPAD https://github.com/moxdlab/KnobService.git knob_clone

mkdir packages/apps/KnobService

cp -r knob_clone/KnobService/* packages/apps/KnobService/ 

rm -rf knob_clone 

```


This is how the folder structure of the KnobService Folder should look like under packages/apps/KnobService:
```text
KnobService/
├── Android.bp                 
├── AndroidManifest.xml      
├── privapp-permissions-knob.xml 
└── src/
    └── com/
        └── example/
            └── knobservice/
                ├── BootReceiver.kt  
                └── KnobService.kt   
```

From your source code directory, enter following command:

```text
curl -L -o device/brcm/rpi4/aosp_rpi4_car.mk https://raw.githubusercontent.com/moxdlab/KnobService/DPAD/aosp_rpi4_car.mk
```

## Build Android Automotive
compile using following commands:
```bash
# Compile the AOSP source code
. build/envsetup.sh
lunch aosp_rpi4_car-userdebug
make -j$(nproc) bootimage systemimage vendorimage
```

## Flahsing the Image

insert the micro SD card into your computer and run the following commands:
```bash
# Check the device name of your SD card (e.g., /dev/sdX)
lsblk
# Unmount the SD card if it is automatically mounted (sdX is just a placeholder)
sudo umount /dev/sdX*
#create the image
./rpi4-mkimg.sh
```


Now, time to write the image to the SD card. Use the following command to fill the sd card:

```bash
 sudo dd if=out/target/product/rpi4/RaspberryVanillaAOSP13-20260611-rpi4.img of=/dev/sdX bs=4M status=progress && sync 
```

After the flashing process is complete, safely eject the SD card and insert it into your Raspberry Pi. 
Power on the Raspberry Pi, and it should boot into Android Automotive with the KnobService running in the background, ready to receive input from the rotary knob.

Note: It can occur, that bluetooth is disabled in the first boot. Enable it and pair with the rotary knob. Restart the Raspberry Pi and you can controll the system with the Rotary Knob.




