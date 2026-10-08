/*
 * Copyright (C) 2026 The Resurrection Remix Project
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

package com.android.systemui.rr.header;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Quick settings header image whose bottom edge fades out into the shade. */
public class QsHeaderImageView extends ImageView {
    private final Paint mFade = new Paint();
    private int mFadeSize;
    private int mShaderHeight = -1;

    public QsHeaderImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    public void setFadeSize(int px) {
        if (mFadeSize != px) {
            mFadeSize = px;
            mShaderHeight = -1;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (mFadeSize <= 0 || w == 0 || h == 0) {
            super.onDraw(canvas);
            return;
        }
        int fade = Math.min(mFadeSize, h);
        if (mShaderHeight != h) {
            mShaderHeight = h;
            mFade.setShader(new LinearGradient(0, h - fade, 0, h,
                    Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        }
        int save = canvas.saveLayer(0, 0, w, h, null);
        super.onDraw(canvas);
        canvas.drawRect(0, h - fade, w, h, mFade);
        canvas.restoreToCount(save);
    }
}
