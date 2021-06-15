package com.android.internal.util.aospextended.clock;

import android.net.Uri;

public class ClockFace {

    private final String mTitle;
    private final String mId;
    private final Uri mPreview;
	private final Uri mThumbnail;

    private ClockFace(String title, String id, Uri preview, Uri thumbnail) {
        mTitle = title;
        mId = id;
        mPreview = preview;
        mThumbnail = thumbnail;
    }

    public String getTitle() {
        return mTitle;
    }

    public Uri getPreviewUri() {
        return mPreview;
    }

    public Uri getThumbnailUri() {
        return mThumbnail;
    }

    public String getId() {
        return mId;
    }

    public static class Builder {
        private String mTitle;
        private String mId;
        private Uri mPreview;
        private Uri mThumbnail;

        public ClockFace build() {
            return new ClockFace(mTitle, mId, mPreview, mThumbnail);
        }

        public Builder setTitle(String title) {
            mTitle = title;
            return this;
        }

        public Builder setId(String id) {
            mId = id;
            return this;
        }

        public Builder setPreview(Uri preview) {
            mPreview = preview;
            return this;
        }

        public Builder setThumbnail(Uri thumbnail) {
            mThumbnail = thumbnail;
            return this;
        }
    }
}
