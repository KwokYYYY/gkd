package li.songe.gkd.data

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable

@Serializable
data class AttrInfo(
    val id: String?,
    val name: String?,
    val text: String?,
    val desc: String?,

    val clickable: Boolean,
    val focusable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val visibleToUser: Boolean,

    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,

    val width: Int,
    val height: Int,

    val childCount: Int,

    val index: Int,
    val depth: Int,
) {
    companion object {
        /**
         * 不要在多线程中使用
         */
        private val rect = Rect()
        fun info2data(
            node: AccessibilityNodeInfo,
            index: Int,
            depth: Int,
        ): AttrInfo {
            node.getBoundsInScreen(rect)
            return AttrInfo(
                id = node.viewIdResourceName,
                name = node.className?.toString(),
                text = node.text?.toString(),
                desc = node.contentDescription?.toString(),

                clickable = node.isClickable,
                focusable = node.isFocusable,
                checkable = node.isCheckable,
                checked = node.isChecked,
                visibleToUser = node.isVisibleToUser,

                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,

                width = rect.width(),
                height = rect.height(),

                childCount = node.childCount,

                index = index,
                depth = depth, //
            )
        }
    }
}
