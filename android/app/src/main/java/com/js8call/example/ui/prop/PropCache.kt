package com.js8call.example.ui.prop

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.js8call.example.prop.Predictor

/**
 * Survives leaving and reopening the Prop tab (activity-scoped), so the tab
 * shows the last picture straight away instead of recomputing everything.
 */
class PropCache : ViewModel() {
    /** What was learned from the logs, with the inputs it was built from. */
    @Volatile var learnedKey: String? = null
    @Volatile var learned: Predictor? = null
    @Volatile var learnedAtMs = 0L

    /** Last painted world heatmap and band chart, keyed by band / time / predictor. */
    @Volatile var bitmapKey: String? = null
    @Volatile var bitmap: Bitmap? = null
    @Volatile var rows: List<StoplightView.Row>? = null
}
