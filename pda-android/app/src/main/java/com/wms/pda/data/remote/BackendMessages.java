package com.wms.pda.data.remote;

/**
 * 后端错误提示语里**被拿来判定**的那些片段(集中在出库扫码 / 盘点扫码 / 探活)。
 *
 * ⚠️ 这些**不是界面文案**,虽然长得像:
 *  - **不要翻译、不要抽进 strings.xml**。判定靠的是和后端返回的 message 做 contains,
 *    一翻就失配,而失配不会报错 —— 会**静默降级**成兜底那句「扫码失败: …」,现场看到的原因就错了。
 *  - **后端改文案会让这里静默失配**。之所以不得不认字:出库扫码的四种拒绝原因
 *    (已出库 / 已报废 / 未锁定给本单 / 已被其他单锁定)在后端**全部是 409**,光看状态码分不开
 *    (只有"码不存在"是 404)。要彻底解耦,得让后端在响应里带一个稳定的 reason,
 *    PDA 再按 reason 分流 —— 那要动后端,见 docs/api-reference.md。
 *  - 后端这两条路径的措辞**并不统一**:抛异常那条是「已出库」,EPC 那条是「EPC 已出库: <epc>」
 *    (见 OutboundService)。现在只靠子串恰好重叠才都能命中,改动时务必两边对照。
 *
 * 「哪些文案进 strings.xml、哪些刻意不进」的完整规则见 docs/pda-design.md §13.2。
 */
public final class BackendMessages {

    /** 后端抛出的原始片段 —— 与 OutboundService / StocktakeService / AuthController 的文案一一对应。 */
    public static final String CODE_NOT_FOUND   = "码不存在";
    public static final String ALREADY_OUT      = "已出库";
    public static final String SCRAPPED         = "已报废";
    public static final String NOT_LOCKED       = "未锁定给本出库单";
    public static final String LOCKED_BY_OTHER  = "已被其他出库单锁定";
    public static final String ALREADY_COUNTED  = "已盘过";
    public static final String NOT_LOGGED_IN    = "未登录";

    private BackendMessages() { }
}
