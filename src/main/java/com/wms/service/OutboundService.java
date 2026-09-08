package com.wms.service;

import com.wms.dao.InventoryItemDao;
import com.wms.dao.InventoryLogDao;
import com.wms.dao.OutboundOrderDao;
import com.wms.dao.ProductDao;
import com.wms.model.InventoryItem;
import com.wms.model.InventoryLog;
import com.wms.model.OutboundOrder;
import com.wms.model.OutboundOrderItem;
import com.wms.model.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OutboundService {
    private final ProductDao productDao; private final InventoryLogDao logDao; private final OutboundOrderDao orderDao; private final InventoryItemDao itemDao;
    @Autowired public OutboundService(ProductDao p,InventoryLogDao l,OutboundOrderDao o,InventoryItemDao i){productDao=p;logDao=l;orderDao=o;itemDao=i;}
    public InventoryLog doOutbound(int productId,int quantity,String remark,String operator){BatchItem i=new BatchItem();i.productId=productId;i.quantity=quantity;i.rowRemark=remark;OutboundOrder o=doOutboundBatch(List.of(i),null,null,remark,operator,null);InventoryLog x=new InventoryLog();x.setType(InventoryLog.TYPE_OUT);x.setProductId(productId);x.setQuantity(quantity);x.setOrderId(o.getId());return x;}
    @Transactional public OutboundOrder doOutboundBatch(List<BatchItem> items,String customer,String externalNo,String orderRemark,String operator){return doOutboundBatch(items,customer,externalNo,orderRemark,operator,null);}
    @Transactional public OutboundOrder doOutboundBatch(List<BatchItem> items,String customer,String externalNo,String orderRemark,String operator,Integer orderId){
        if(items==null||items.isEmpty())throw new IllegalArgumentException("出库明细不能为空");if(operator==null||operator.isBlank())operator="操作员";Map<Integer,BatchItem> m=new LinkedHashMap<>();for(BatchItem r:items){if(r.productId==null)throw new IllegalArgumentException("请选择商品");if(r.quantity==null||r.quantity<=0)throw new IllegalArgumentException("出库数量必须大于 0");BatchItem a=m.computeIfAbsent(r.productId,k->{BatchItem z=new BatchItem();z.productId=k;z.quantity=0;z.rowRemark=r.rowRemark;return z;});a.quantity+=r.quantity;}
        OutboundOrder o=orderId==null?new OutboundOrder():orderDao.findOrderById(orderId);if(orderId!=null&&(o==null||!OutboundOrder.STATUS_DRAFT.equals(o.getStatus())))throw new IllegalStateException("出库单不是草稿");if(orderId==null){o.setOrderNo(genOrderNo());o.setCustomer(blank(customer));o.setExternalNo(blank(externalNo));o.setRemark(blank(orderRemark));o.setOperator(operator);o.setStatus(OutboundOrder.STATUS_COMMITTED);o.setTotalQuantity(m.values().stream().mapToInt(x->x.quantity).sum());o.setItemCount(m.size());int id=orderDao.insertOrder(o);o.setId(id);}
        for(BatchItem b:m.values()){Product p=productDao.findById(b.productId).orElseThrow(()->new IllegalArgumentException("商品不存在, id="+b.productId));String loc=blank(p.getLocation())==null?"默认库位":p.getLocation();Long av=itemDao.sumRemaining(p.getId(),loc);if((av==null?0:av)<b.quantity&&!(p.getQuantity()>=b.quantity&&(av==null||av==0)))throw new IllegalStateException("库存不足");if(orderId==null){int before=p.getQuantity();if(productDao.adjustQuantity(b.productId,-b.quantity)==0)throw new IllegalStateException("出库失败");itemDao.consumeForOutbound(p.getId(),loc,b.quantity,o.getId());OutboundOrderItem it=new OutboundOrderItem();it.setOrderId(o.getId());it.setProductId(p.getId());it.setSku(p.getSku());it.setProductName(p.getName());it.setQuantity(b.quantity);it.setUnitPrice(p.getUnitPrice()==null?BigDecimal.ZERO:p.getUnitPrice());it.setBeforeQty(before);it.setAfterQty(before-b.quantity);it.setLocation(blank(p.getLocation()));it.setRowRemark(blank(b.rowRemark));orderDao.insertItem(it);}}
        if(orderId!=null){orderDao.refreshAggregates(orderId);o=orderDao.findOrderById(orderId);}o.setItems(orderDao.findItemsByOrderId(o.getId()));return o;
    }
    @Transactional public OutboundOrder createDraft(String customer,String externalNo,String remark,String operator){if(operator==null||operator.isBlank())operator="操作员";OutboundOrder o=new OutboundOrder();o.setOrderNo(genOrderNo());o.setCustomer(blank(customer));o.setExternalNo(blank(externalNo));o.setRemark(blank(remark));o.setOperator(operator);o.setTotalQuantity(0);o.setItemCount(0);o.setStatus(OutboundOrder.STATUS_DRAFT);int id=orderDao.insertOrder(o);o.setId(id);return getOrder(id);}
    @Transactional public OutboundOrder scanCodeToDraft(int orderId,String code){OutboundOrder o=requireDraft(orderId);InventoryItem inv=itemDao.findByCode(code);if(inv==null)throw new IllegalArgumentException("码不存在");if(InventoryItem.STATUS_OUT.equals(inv.getStatus()))throw new IllegalStateException("已出库");if(InventoryItem.STATUS_SCRAPPED.equals(inv.getStatus()))throw new IllegalStateException("已报废");if(!InventoryItem.STATUS_IN_STOCK.equals(inv.getStatus()))throw new IllegalStateException("库存状态不可出库");Product p=productDao.findById(inv.getProductId()).orElseThrow(()->new IllegalArgumentException("商品不存在"));List<OutboundOrderItem> lines=orderDao.findItemsByOrderId(orderId);OutboundOrderItem line=lines.stream().filter(x->x.getProductId().equals(inv.getProductId())).findFirst().orElse(null);if(line==null){line=new OutboundOrderItem();line.setOrderId(orderId);line.setProductId(p.getId());line.setSku(p.getSku());line.setProductName(p.getName());line.setQuantity(1);line.setUnitPrice(p.getUnitPrice()==null?BigDecimal.ZERO:p.getUnitPrice());line.setLocation(blank(p.getLocation()));line.setBeforeQty(p.getQuantity());line.setAfterQty(p.getQuantity()-1);orderDao.insertItem(line);}else{orderDao.updateItemQuantity(line.getId(),line.getQuantity()+1);}orderDao.refreshAggregates(orderId);return getOrder(orderId);}
    @Transactional public OutboundOrder commitDraft(int id){OutboundOrder o=requireDraft(id);List<OutboundOrderItem> lines=orderDao.findItemsByOrderId(id);if(o.getTotalQuantity()==null||o.getTotalQuantity()<=0)throw new IllegalStateException("草稿单不能为空");for(OutboundOrderItem x:lines){Product p=productDao.findById(x.getProductId()).orElseThrow(()->new IllegalArgumentException("商品不存在"));String loc=blank(p.getLocation())==null?"默认库位":p.getLocation();if((itemDao.sumRemaining(p.getId(),loc)==null?0:itemDao.sumRemaining(p.getId(),loc))<x.getQuantity())throw new IllegalStateException("库存不足");if(productDao.adjustQuantity(p.getId(),-x.getQuantity())==0)throw new IllegalStateException("出库失败");itemDao.consumeForOutbound(p.getId(),loc,x.getQuantity(),id);}orderDao.updateStatus(id,OutboundOrder.STATUS_COMMITTED);return getOrder(id);}
    @Transactional public void cancelDraft(int id){OutboundOrder o=requireDraft(id);orderDao.deleteItemsByOrderId(id);orderDao.updateStatus(id,OutboundOrder.STATUS_CANCELLED);}
    private OutboundOrder requireDraft(int id){OutboundOrder o=orderDao.findOrderById(id);if(o==null)throw new IllegalArgumentException("出库单不存在, id="+id);if(!OutboundOrder.STATUS_DRAFT.equals(o.getStatus()))throw new IllegalStateException("出库单不是草稿");return o;}
    @Transactional(readOnly=true) public OutboundOrder getOrder(int id){OutboundOrder o=orderDao.findOrderById(id);if(o!=null)o.setItems(orderDao.findItemsByOrderId(id));return o;}
    @Transactional(readOnly=true) public List<OutboundOrder> listOrders(String b,String e,String k,String op,String st,int p,int s){return orderDao.findOrders(b,e,k,op,st,p,s);}
    @Transactional(readOnly=true) public long countOrders(String b,String e,String k,String op,String st){return orderDao.countOrders(b,e,k,op,st);}
    private static String genOrderNo(){return "OUT-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+"-"+(int)(Math.random()*900+100);}private static String blank(String s){return s==null||s.trim().isEmpty()?null:s.trim();}
    public static class BatchItem{public Integer productId;public Integer quantity;public String rowRemark;}
}
