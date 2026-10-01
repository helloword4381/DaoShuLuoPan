package com.daoshu.compass.ui.compass

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------
// 罗盘名称表（与 CONTRACT 第 7 节逐字一致，顺序即盘面顺时针顺序）
// ---------------------------------------------------------------------------

/** 二十四山：自正北（子）顺时针，每山 15° */
val MOUNTAIN_NAMES: List<String> = listOf(
    "子", "癸", "丑", "艮", "寅", "甲", "卯", "乙",
    "辰", "巽", "巳", "丙", "午", "丁", "未", "坤",
    "申", "庚", "酉", "辛", "戌", "乾", "亥", "壬"
)

/** 八卦：后天八卦顺序（坎北 → 艮东北 → 震东 → 巽东南 → 离南 → 坤西南 → 兑西 → 乾西北） */
val BAGUA_NAMES: List<String> = listOf("坎", "艮", "震", "巽", "离", "坤", "兑", "乾")

/** 二十八宿：自正北起顺时针 */
val XIU_NAMES: List<String> = listOf(
    "斗", "牛", "女", "虚", "危", "室", "壁", "奎",
    "娄", "胃", "昴", "毕", "觜", "参", "井", "鬼",
    "柳", "星", "张", "翼", "轸", "角", "亢", "氐",
    "房", "心", "尾", "箕"
)

/** 十天干 */
const val HEAVENLY_STEMS: String = "甲乙丙丁戊己庚辛壬癸"

/** 十二地支 */
const val EARTHLY_BRANCHES: String = "子丑寅卯辰巳午未申酉戌亥"

/**
 * 六十甲子：由十天干与十二地支依次相配生成（甲子 → 癸亥，共 60）。
 * 自正北起顺时针排布，每格 6°。
 */
val JIAZI_NAMES: List<String> = List(60) { index ->
    "${HEAVENLY_STEMS[index % 10]}${EARTHLY_BRANCHES[index % 12]}"
}

/**
 * 二十四山所属卦宫索引（对应 [BAGUA_NAMES] 下标）。
 * 坎宫三山 = 壬子癸（壬在盘面末尾，紧邻 0° 逆时针侧），艮宫 = 丑艮寅，依此类推。
 */
val MOUNTAIN_PALACE_INDEX: List<Int> = listOf(
    0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4,
    4, 4, 5, 5, 5, 6, 6, 6, 7, 7, 7, 0
)

/** 八卦方位色（坎艮震巽离坤兑乾），深色/浅色盘面均可辨识 */
val BAGUA_COLORS: List<Color> = listOf(
    Color(0xFF3E7FA8), // 坎 · 玄蓝（北）
    Color(0xFF8A7A45), // 艮 · 土黄（东北）
    Color(0xFF4E8F5A), // 震 · 青绿（东）
    Color(0xFF3FA08A), // 巽 · 碧（东南）
    Color(0xFFC25A44), // 离 · 朱（南）
    Color(0xFFA9863C), // 坤 · 土金（西南）
    Color(0xFF8E97A8), // 兑 · 素银（西）
    Color(0xFF9A86C4)  // 乾 · 紫（西北）
)

/** 八卦方位角（顺时针自正北），索引与 [BAGUA_NAMES] 对应 */
val BAGUA_BEARINGS: FloatArray = FloatArray(BAGUA_NAMES.size) { it * 45f }

// ---------------------------------------------------------------------------
// 预计算三角函数表：避免在每帧 Canvas 绘制中调用 sin/cos
// ---------------------------------------------------------------------------

/** 0..359 整数度的余弦表 */
val COS_TABLE: FloatArray = FloatArray(360) { cos(Math.toRadians(it.toDouble())).toFloat() }

/** 0..359 整数度的正弦表 */
val SIN_TABLE: FloatArray = FloatArray(360) { sin(Math.toRadians(it.toDouble())).toFloat() }

/** 取整度数对应的 cos（自动取模，保证索引安全） */
fun cosDegree(degrees: Int): Float = COS_TABLE[((degrees % 360) + 360) % 360]

/** 取整度数对应的 sin（自动取模，保证索引安全） */
fun sinDegree(degrees: Int): Float = SIN_TABLE[((degrees % 360) + 360) % 360]

// ---------------------------------------------------------------------------
// 通用计算
// ---------------------------------------------------------------------------

/** 角度归一化到 0..360 */
fun normalizeDegrees(value: Float): Float {
    val normalized = value % 360f
    return if (normalized < 0f) normalized + 360f else normalized
}

/** 八方位名称，索引 0 = 正北 */
private val DIRECTION_NAMES: List<String> = listOf(
    "正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北"
)

/** 朝向角度 → 中文方位名（用于读数显示） */
fun bearingLabel(degrees: Float): String {
    val index = (((normalizeDegrees(degrees) + 22.5f) / 45f).toInt()) % DIRECTION_NAMES.size
    return DIRECTION_NAMES[index]
}
