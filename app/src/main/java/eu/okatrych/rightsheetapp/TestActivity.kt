package eu.okatrych.rightsheetapp

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import eu.okatrych.rightsheet.RightSheetBehavior
import kotlinx.android.synthetic.main.activity_test.*

class TestActivity : AppCompatActivity() {

    private lateinit var rightSheetBehavior: RightSheetBehavior<FrameLayout>
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)
        initViews()
    }

    private fun initViews() {
        rightSheetBehavior = RightSheetBehavior.from(right_sheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottom_sheet)

        btn_open_right_sheet.setOnClickListener {
            rightSheetBehavior.state = RightSheetBehavior.STATE_EXPANDED
        }
        btn_open_bottom_sheet.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }
}
