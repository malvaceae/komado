package me.mallows.komado

import android.content.Context
import android.view.View
import android.widget.ScrollView

/** Keep at least half the available space for the video when controls are tall. */
internal class VideoControlsScrollView(context: Context) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
        val limited = if (mode == View.MeasureSpec.UNSPECIFIED) heightMeasureSpec else
            View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(heightMeasureSpec) / 2, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, limited)
    }
}
