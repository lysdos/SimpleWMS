package com.wms.service;

import com.wms.dao.InventoryItemDao;
import com.wms.dao.InventoryLogDao;
import com.wms.dao.OutboundOrderDao;
import com.wms.dao.ProductDao;
import com.wms.model.OutboundOrder;
import com.wms.model.OutboundOrderItem;
import com.wms.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OutboundService#addItemToDraft} 的**合并语义**测试。
 *
 * 为什么要单独测:出库单元只记 outbound_order_id(指向单据),没有指向明细行的字段,
 * 所以详情页给每一行填码/拣货分布时只能按「单据+商品」查 —— 同商品一旦有两行,
 * 两行都会显示该商品的全部码与全部拣货量(数量虚高,打印出来现场会按双倍去拿)。
 * PDA 的「添加明细」是唯一能造出这种单的入口,必须保证它并进已有行。
 *
 * 用纯 Mockito(不启 Spring、不连库):这是 service 层的判定逻辑,DAO 全是 mock 就够,
 * 也免得为了测一个分支引入测试库。
 */
@ExtendWith(MockitoExtension.class)
class OutboundServiceAddItemTest {

    private static final int ORDER_ID = 7;

    @Mock private ProductDao productDao;
    @Mock private InventoryLogDao logDao;
    @Mock private OutboundOrderDao orderDao;
    @Mock private InventoryItemDao itemDao;

    private OutboundService service;

    @BeforeEach
    void setUp() {
        service = new OutboundService(productDao, logDao, orderDao, itemDao);
    }

    private Product product(int id) {
        Product p = new Product();
        p.setId(id);
        p.setSku("SP-00" + id);
        p.setName("商品" + id);
        p.setQuantity(50);
        p.setUnitPrice(new BigDecimal("10.00"));
        return p;
    }

    private OutboundOrder draft() {
        OutboundOrder o = new OutboundOrder();
        o.setId(ORDER_ID);
        o.setOrderNo("OUT-TEST");
        o.setStatus(OutboundOrder.STATUS_DRAFT);
        return o;
    }

    private OutboundOrderItem line(int id, int productId, int qty) {
        OutboundOrderItem it = new OutboundOrderItem();
        it.setId(id);
        it.setOrderId(ORDER_ID);
        it.setProductId(productId);
        it.setQuantity(qty);
        return it;
    }

    /** 该单已有同商品的行(5 件),再追加 3 件 → 并进那一行变 8 件,且不新插行 */
    @Test
    void existingSameProductLine_mergesIntoIt_andDoesNotInsert() {
        when(orderDao.findOrderByIdForUpdate(ORDER_ID)).thenReturn(draft());
        when(productDao.findById(2)).thenReturn(Optional.of(product(2)));
        when(orderDao.findItemsByOrderId(ORDER_ID)).thenReturn(new ArrayList<>(List.of(line(11, 2, 5))));

        service.addItemToDraft(ORDER_ID, batchItem(2, 3));

        verify(orderDao).updateItemQuantity(eq(11), eq(8));
        verify(orderDao, never()).insertItem(any());
        // 只锁这次追加的 3 件,不是把整行 8 件再锁一遍
        verify(itemDao).reserveForOutbound(eq(2), eq(null), eq(3), eq(ORDER_ID));
    }

    /** 该单只有别的商品的行 → 正常新插一行(不能误并到别的商品上) */
    @Test
    void onlyOtherProductLines_insertsNewLine() {
        when(orderDao.findOrderByIdForUpdate(ORDER_ID)).thenReturn(draft());
        when(productDao.findById(2)).thenReturn(Optional.of(product(2)));
        when(orderDao.findItemsByOrderId(ORDER_ID)).thenReturn(new ArrayList<>(List.of(line(11, 99, 5))));

        service.addItemToDraft(ORDER_ID, batchItem(2, 3));

        verify(orderDao).insertItem(any());
        verify(orderDao, never()).updateItemQuantity(anyInt(), anyInt());
    }

    /** 空单 → 新插一行 */
    @Test
    void emptyOrder_insertsNewLine() {
        when(orderDao.findOrderByIdForUpdate(ORDER_ID)).thenReturn(draft());
        when(productDao.findById(2)).thenReturn(Optional.of(product(2)));
        when(orderDao.findItemsByOrderId(ORDER_ID)).thenReturn(new ArrayList<>());

        service.addItemToDraft(ORDER_ID, batchItem(2, 3));

        verify(orderDao).insertItem(any());
        verify(orderDao, never()).updateItemQuantity(anyInt(), anyInt());
    }

    /**
     * 库存不足时:异常抛出去让事务回滚,而且**不能**先改行 ——
     * 所以锁货必须排在改动之前(这就是把 reserveForOutbound 提到最前的原因)。
     */
    @Test
    void insufficientStock_leavesNoLineChange() {
        when(orderDao.findOrderByIdForUpdate(ORDER_ID)).thenReturn(draft());
        when(productDao.findById(2)).thenReturn(Optional.of(product(2)));
        when(orderDao.findItemsByOrderId(ORDER_ID)).thenReturn(new ArrayList<>(List.of(line(11, 2, 5))));
        when(itemDao.reserveForOutbound(eq(2), eq(null), eq(3), eq(ORDER_ID)))
                .thenThrow(new IllegalStateException("库存不足:需要 3 件,只能锁 0 件"));

        assertThrows(IllegalStateException.class, () -> service.addItemToDraft(ORDER_ID, batchItem(2, 3)));

        verify(orderDao, never()).updateItemQuantity(anyInt(), anyInt());
        verify(orderDao, never()).insertItem(any());
    }

    /** 非草稿单不能追加(requireDraft 的守卫不能被这次改动绕过) */
    @Test
    void nonDraftOrder_isRejected() {
        OutboundOrder committed = draft();
        committed.setStatus(OutboundOrder.STATUS_COMMITTED);
        when(orderDao.findOrderByIdForUpdate(ORDER_ID)).thenReturn(committed);

        assertThrows(IllegalStateException.class, () -> service.addItemToDraft(ORDER_ID, batchItem(2, 3)));

        verify(orderDao, never()).insertItem(any());
        verify(orderDao, never()).updateItemQuantity(anyInt(), anyInt());
    }

    /**
     * 单头必须走**加锁读**(FOR UPDATE),而且必须是这条事务里早于一切写入的那次读。
     *
     * 改明细的 DAO 写入都不带 `AND status='DRAFT'` 守卫(守卫只在 commit/cancel 的最后一步),
     * 所以并发下"追加明细"能穿过已提交的单 —— 那几件刚锁的货再没人释放(状态 RESERVED、
     * 所属单却是 COMMITTED),product.quantity 也跟着少算。锁只有落在事务的第一条读上,
     * 后到者排队拿到锁时才会看到状态已变,走上面那条 409 分支。
     *
     * 注:方法末尾 getOrder 回读单头走的是普通 findOrderById(只读、不参与加锁),那是正常的,
     * 所以这里用 InOrder 钉"加锁读在最前",而不是"从不用普通读"。
     */
    @Test
    void headerIsReadWithRowLock_beforeAnyWrite() {
        when(orderDao.findOrderByIdForUpdate(ORDER_ID)).thenReturn(draft());
        when(productDao.findById(2)).thenReturn(Optional.of(product(2)));
        when(orderDao.findItemsByOrderId(ORDER_ID)).thenReturn(new ArrayList<>(List.of(line(11, 2, 5))));

        service.addItemToDraft(ORDER_ID, batchItem(2, 3));

        InOrder inOrder = inOrder(orderDao, itemDao);
        inOrder.verify(orderDao).findOrderByIdForUpdate(ORDER_ID);
        inOrder.verify(itemDao).reserveForOutbound(eq(2), eq(null), eq(3), eq(ORDER_ID));
        inOrder.verify(orderDao).updateItemQuantity(eq(11), eq(8));
    }

    private OutboundService.BatchItem batchItem(int productId, int qty) {
        OutboundService.BatchItem b = new OutboundService.BatchItem();
        b.productId = productId;
        b.quantity = qty;
        return b;
    }
}
