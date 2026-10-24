package com.clean.tvhome;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** Motion tokens. Nothing animates at idle; nothing takes longer than a page shift. */
final class Motion {
    static final int FOCUS_MS = 150;   // outline, scale, name reveal
    static final int ROW_MS = 250;     // page scroll by one row
    static final int PANEL_MS = 200;   // inputs panel open and close
    static final int REVEAL_MS = 400;  // the clock settling once, on launch
    static final float FOCUS_SCALE = 1.08f;  // the rail concept's larger tiles take a touch more
    static final float PRESS_SCALE = 0.98f;

    static Interpolator standard() {
        return new PathInterpolator(0.2f, 0f, 0f, 1f);
    }

    private Motion() {}
}
