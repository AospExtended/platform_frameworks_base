/* Copyright (c) 2018, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */


package android.net.wifi.p2p;

import android.os.Parcelable;
import android.os.Parcel;

import java.util.Locale;
import java.util.ArrayList;

/**
 * A class representing Wifi Display information for a device
 * @hide
 */
public class WifiWscVendorInfo implements Parcelable {

    private static final String TAG = "WifiWscVendorInfo";

    private byte mCapabilityInfo;

    private String mHostName;

    private String mBssid;//Mostly unused
    private boolean mbBssidPresent;

    private int mConnectionPreference;

    private String mIpAddress;
    public  String mP2pMacAddress;

    public static final int CAPABILITY_ATTRIBUTE             = 0x2001;
    public static final int HOSTNAME_ATTRIBUTE               = 0x2002;
    public static final int BSSID_ATTRIBUTE                  = 0x2003;
    public static final int CONNECTION_PREFERENCE_ATTRIBUTE  = 0x2004;
    public static final int IPADDRESS_ATTRIBUTE              = 0x2005;

    public static final byte WSC_VENDOR                       = 0;
    public static final byte MIRRORLINK                       = 1;
    public WifiWscVendorInfo() {
    }

    public WifiWscVendorInfo(byte capabilityInfo, String hostName,
            String bssId, int connectionPref, String ipAddress, String macAddress) {
        mCapabilityInfo = capabilityInfo;
        mHostName = hostName;
        mBssid = bssId;
        mbBssidPresent = false;
        mConnectionPreference = connectionPref;
        mIpAddress = ipAddress;
        mP2pMacAddress = macAddress;
    }

    public byte getCapabilityInfo() {
        return mCapabilityInfo;
    }

    public String getHostName() {
        return (mHostName);
    }

    public String getBssId() {
        return (mBssid);
    }

    public int getConnectionPreference() {
        return (mConnectionPreference);
    }

    public String getIpAddress() {
        return (mIpAddress);
    }

    public void setCapability(byte cap) {
        mCapabilityInfo = cap;
    }

    public void setHostName(String hostName) {
        mHostName = hostName;
    }

    public void setBssid(String bssId) {
        mbBssidPresent = true;
        mBssid = bssId;
    }

    public void setConnectionPreference(int conPref) {
        mConnectionPreference = conPref;
    }

    public void setIpAddress(String ipaddress) {
        mIpAddress = ipaddress;
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("\n WSC Vendor Capability: ").append(mCapabilityInfo);
        sbuf.append("\n WSC Vendor HostName: ").append(mHostName);
        sbuf.append("\n WSC Vendor BSSID: ").append(mBssid);
        sbuf.append("\n WSC Vendor Connection Preference: ").append(mConnectionPreference);
        sbuf.append("\n WSC Vendor IP Address: ").append(mIpAddress);
        sbuf.append("\n WSC Vendor macAddress : ").append(mP2pMacAddress);
        return sbuf.toString();
    }

    public ArrayList<Byte> toArray() {

        ArrayList<Byte> info = new ArrayList<Byte>();
        int length = 0;

        //WPS OUI
        info.add((byte)0x0);
        info.add((byte)0x01);
        info.add((byte)0x37);
        length+=2;

        //Capability Attribute
        info.add((byte)0x20);
        info.add((byte)0x01);
        info.add((byte)0x00);
        info.add((byte)0x01);
        info.add(mCapabilityInfo);
        length+=5;

        //Hostname Attribute
        info.add((byte)0x20);
        info.add((byte)0x02);
        info.add((byte)((mHostName.length()>>8)));
        info.add((byte)mHostName.length());
        for (int i =0 ; i < mHostName.length(); i++)
            info.add((byte)mHostName.charAt(i));
        length += (mHostName.length()+4);

        //BSSID Attribute
        if (mbBssidPresent) {
            info.add((byte)0x20);
            info.add((byte)0x03);
            info.add((byte)0x00);
            info.add((byte)0x06);
            for(int i = 0 ; i < 6; i++)
                info.add((byte)mBssid.charAt(i));
            length+= 10;
        }

        //Connection Preference Attribute
        info.add((byte)0x20);
        info.add((byte)0x04);
        info.add((byte)0x00);
        info.add((byte)0x04);
        info.add((byte)((mConnectionPreference&0xff000000)>>24));
        info.add((byte)((mConnectionPreference&0x00ff0000)>>16));
        info.add((byte)((mConnectionPreference&0x0000ff00)>>8));
        info.add((byte)(mConnectionPreference&0x000000ff));
        length +=8;

        //IPAddress Attribute
        info.add((byte)0x20);
        info.add((byte)0x05);
        info.add((byte)(mIpAddress.length()));
        info.add((byte)(0x0));
        for (int i =0 ; i < mIpAddress.length(); i++)
            info.add((byte)mIpAddress.charAt(i));
        length += (mIpAddress.length()+4);
        return info;
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** copy constructor */
    public WifiWscVendorInfo(WifiWscVendorInfo source) {
        if (source != null) {
            mCapabilityInfo = source.mCapabilityInfo;
            mHostName = source.mHostName;
            mBssid = source.mBssid;
            mConnectionPreference = source.mConnectionPreference;
            mIpAddress = source.mIpAddress;
            mP2pMacAddress = source.mP2pMacAddress;
        }
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte(mCapabilityInfo);
        dest.writeString(mHostName);
        dest.writeString(mBssid);
        dest.writeInt(mConnectionPreference);
        dest.writeString(mIpAddress);
        dest.writeString(mP2pMacAddress);
    }

    public void readFromParcel(Parcel in) {
        mCapabilityInfo = in.readByte();
        mHostName = in.readString();
        mBssid = in.readString();
        mConnectionPreference = in.readInt();
        mIpAddress = in.readString();
        mP2pMacAddress = in.readString();
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiWscVendorInfo> CREATOR =
        new Creator<WifiWscVendorInfo>() {
            public WifiWscVendorInfo createFromParcel(Parcel in) {
                WifiWscVendorInfo device = new WifiWscVendorInfo();
                device.readFromParcel(in);
                return device;
            }

            public WifiWscVendorInfo[] newArray(int size) {
                return new WifiWscVendorInfo[size];
            }
        };
}
