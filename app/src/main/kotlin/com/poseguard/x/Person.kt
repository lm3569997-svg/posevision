package com.poseguard.x

import android.graphics.PointF

/**
 * 检测到的人。
 * 坐标是「原始帧像素」，绘制时按画面与屏幕的比例换算。
 */
data class Person(
    val id: Int,
    val kps: List<PointF>,      // 17 点，COCO 顺序
    val kpConf: FloatArray,
    val score: Float,
    val box: FloatArray         // left, top, right, bottom（像素）
) {
    fun ok(i: Int): Boolean = i in kps.indices && kpConf[i] >= Kps.THRESH
}

/** COCO 17 关键点 */
object Kps {
    const val NOSE = 0
    const val L_EYE = 1
    const val R_EYE = 2
    const val L_EAR = 3
    const val R_EAR = 4
    const val L_SHOULDER = 5
    const val R_SHOULDER = 6
    const val L_ELBOW = 7
    const val R_ELBOW = 8
    const val L_WRIST = 9
    const val R_WRIST = 10
    const val L_HIP = 11
    const val R_HIP = 12
    const val L_KNEE = 13
    const val R_KNEE = 14
    const val L_ANKLE = 15
    const val R_ANKLE = 16

    const val COUNT = 17
    const val THRESH = 0.28f

    /** 骨架连线 */
    val BONES: Array<IntArray> = arrayOf(
        intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(2, 4),
        intArrayOf(5, 6), intArrayOf(5, 11), intArrayOf(6, 12), intArrayOf(11, 12),
        intArrayOf(5, 7), intArrayOf(7, 9), intArrayOf(6, 8), intArrayOf(8, 10),
        intArrayOf(11, 13), intArrayOf(13, 15), intArrayOf(12, 14), intArrayOf(14, 16)
    )

    /** 每人一种颜色（青 / 粉 / 黄 / 紫 / 绿） */
    val COLORS: IntArray = intArrayOf(
        0xFF00E5FF.toInt(),
        0xFFFF4FA3.toInt(),
        0xFFFFD54F.toInt(),
        0xFFB388FF.toInt(),
        0xFF69F0AE.toInt()
    )
}
