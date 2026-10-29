package com.wms.model;

import java.util.List;

/**
 * 分页结果。
 *
 * 为什么单独建一个类型,而不是像出入库单那样用 {@code Map.of("list",..,"total",..)}:
 * 基础管理有 5 个模块要分页,五份 Map 的键名迟早漂移(有人写 total,有人写 count),
 * 而且 Map 里的元素类型不受约束,前端拿到什么全靠约定。
 *
 * 字段名与出入库单列表保持一致(list / total / page / pageSize),
 * 前端的分页器可以共用一套。
 */
public class PageResult<T> {

    private List<T> list;
    private long total;
    private int page;
    private int pageSize;

    public PageResult() {}

    public PageResult(List<T> list, long total, int page, int pageSize) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    /** 总页数(至少 1 —— 空结果也算一页,前端显示的「第 1/1 页」才不怪) */
    public int getPages() {
        if (pageSize <= 0) return 1;
        return Math.max(1, (int) ((total + pageSize - 1) / pageSize));
    }

    /**
     * 把请求的页码钳进 [1, 总页数]。
     *
     * 为什么需要:别人删掉几行后,你还停在第 3 页,再刷新就会拿到空列表,
     * 分页器显示「第 3 / 2 页」—— 看起来像坏了。钳到最后一页更有用。
     * 五个模块共用这一处,免得各写一遍 Math.min/Math.max。
     */
    public static int clampPage(int requested, int pageSize, long total) {
        int ps = pageSize <= 0 ? 20 : pageSize;
        int pages = (int) Math.max(1, (total + ps - 1) / ps);
        if (requested < 1) return 1;
        return Math.min(requested, pages);
    }

    public List<T> getList() { return list; }
    public void setList(List<T> v) { this.list = v; }
    public long getTotal() { return total; }
    public void setTotal(long v) { this.total = v; }
    public int getPage() { return page; }
    public void setPage(int v) { this.page = v; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int v) { this.pageSize = v; }
}
