import React, { useEffect, useState } from 'react';
import { ActivityIndicator, FlatList, Modal, Platform, Pressable, RefreshControl, SafeAreaView, ScrollView, StatusBar, StyleSheet, Text, TextInput, View } from 'react-native';
import * as Notifications from 'expo-notifications';
import AsyncStorage from '@react-native-async-storage/async-storage';
import Constants from 'expo-constants';

Notifications.setNotificationHandler({
  handleNotification: async () => ({ shouldShowAlert: true, shouldPlaySound: true, shouldSetBadge: true })
});

const API_URL = Constants.expoConfig?.extra?.apiUrl || 'https://bkr-fastfood-backend-6snv.onrender.com/api';
const POLL_MS = 30000;

const GOLD = '#e8b84b';
const GOLD_BRIGHT = '#f6ce6a';
const RED = '#a52322';
const RED_DARK = '#7a1918';
const BLACK = '#0e0c0b';
const CARD = '#1c1815';
const CREAM = '#f3ead9';

const formatMoney = (value) => `Rs ${Number(value || 0).toLocaleString('en-PK', { maximumFractionDigits: 0 })}`;
const today = () => new Date().toISOString().slice(0, 10);

async function getJson(path) {
  const response = await fetch(`${API_URL}${path}`);
  if (!response.ok) throw new Error(`Request failed (${response.status})`);
  return response.json();
}

async function notifyNewOrders(orders) {
  if (!orders.length) return;
  const previous = await AsyncStorage.getItem('lastOrderId');
  const newest = String(Math.max(...orders.map((order) => Number(order.orderId || 0))));
  if (previous && Number(newest) > Number(previous)) {
    const count = orders.filter((order) => Number(order.orderId) > Number(previous)).length;
    await Notifications.scheduleNotificationAsync({
      content: { title: 'New BKR order', body: `${count} new order${count === 1 ? '' : 's'} received.` },
      trigger: null
    });
  }
  if (!previous || Number(newest) > Number(previous)) await AsyncStorage.setItem('lastOrderId', newest);
}

/** Pure RN approximation of the BKR badge: gold ring, red dome, arched wordmark. */
function BkrLogoBadge({ size = 64 }) {
  const ring = size;
  const dome = size - Math.max(4, Math.round(size * 0.09));
  return (
    <View style={[styles.logoRing, { width: ring, height: ring, borderRadius: ring / 2 }]}>
      <View style={[styles.logoDome, { width: dome, height: dome, borderRadius: dome / 2 }]}>
        <Text style={[styles.logoBkr, { fontSize: size * 0.34 }]}>BKR</Text>
        <View style={styles.logoBanner}>
          <Text style={styles.logoBannerText}>BACHA KHAN</Text>
        </View>
      </View>
    </View>
  );
}

function OrderDetailModal({ visible, onClose, loading, error, order }) {
  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <View style={styles.modalOverlay}>
        <View style={styles.modalCard}>
          <View style={styles.modalHeader}>
            <Text style={styles.modalTitle}>{order ? (order.orderNo || `Order #${order.orderId}`) : 'Order Details'}</Text>
            <Pressable onPress={onClose} hitSlop={10}><Text style={styles.modalClose}>Close</Text></Pressable>
          </View>
          {loading ? (
            <ActivityIndicator size="large" color={GOLD} style={{ marginVertical: 30 }} />
          ) : error ? (
            <Text style={styles.errorText}>{error}</Text>
          ) : order ? (
            <ScrollView style={{ maxHeight: 420 }}>
              <Text style={styles.modalMeta}>{order.customerName || 'Walk-in'}  ·  {order.orderType || 'Dine In'}  ·  {order.paymentType || 'Cash'}</Text>
              <Text style={styles.modalMeta}>{order.orderDate || ''} {order.orderTime || ''}</Text>
              <View style={styles.modalDivider} />
              {(order.items || []).map((line, index) => (
                <View key={index} style={styles.lineRow}>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.lineName}>{line.menuItemName}</Text>
                    <Text style={styles.lineQty}>Qty {line.quantity} × {formatMoney(line.unitPrice)}</Text>
                  </View>
                  <Text style={styles.lineTotal}>{formatMoney(line.lineTotal)}</Text>
                </View>
              ))}
              <View style={styles.modalDivider} />
              <View style={styles.totalsRow}><Text style={styles.totalsLabel}>Subtotal</Text><Text style={styles.totalsValue}>{formatMoney(order.subtotal)}</Text></View>
              {Number(order.discount) > 0 ? <View style={styles.totalsRow}><Text style={styles.totalsLabel}>Discount</Text><Text style={styles.totalsValue}>-{formatMoney(order.discount)}</Text></View> : null}
              {Number(order.deliveryCharge) > 0 ? <View style={styles.totalsRow}><Text style={styles.totalsLabel}>Delivery</Text><Text style={styles.totalsValue}>{formatMoney(order.deliveryCharge)}</Text></View> : null}
              {Number(order.serviceCharge) > 0 ? <View style={styles.totalsRow}><Text style={styles.totalsLabel}>Service ({order.serviceChargePercent}%)</Text><Text style={styles.totalsValue}>{formatMoney(order.serviceCharge)}</Text></View> : null}
              <View style={styles.totalsRow}><Text style={styles.grandLabel}>Total</Text><Text style={styles.grandValue}>{formatMoney(order.totalAmount)}</Text></View>
            </ScrollView>
          ) : null}
        </View>
      </View>
    </Modal>
  );
}

function InventoryTab({ inventory, loading, error, onRefresh }) {
  return (
    <ScrollView contentContainerStyle={styles.tabContent} refreshControl={<RefreshControl refreshing={loading} onRefresh={onRefresh} tintColor={GOLD} />}>
      <Text style={styles.pageTitle}>Current inventory</Text>
      <Text style={styles.pageHint}>Live stock quantities from the restaurant system</Text>
      {error ? <Pressable onPress={onRefresh} style={styles.error}><Text style={styles.errorTitle}>Connection issue</Text><Text style={styles.errorText}>{error}. Tap to retry.</Text></Pressable> : null}
      {inventory.map((item) => {
        const low = Number(item.quantity) <= Number(item.reorderLevel);
        return (
          <View key={String(item.itemId)} style={[styles.inventoryRow, low && styles.inventoryRowLow]}>
            <View style={styles.inventoryInfo}>
              <Text style={styles.inventoryName}>{item.itemName}</Text>
              <Text style={styles.inventoryMeta}>Reorder at {item.reorderLevel || 0}</Text>
            </View>
            <Text style={[styles.inventoryQuantity, low && styles.inventoryQuantityLow]}>{Number(item.quantity || 0).toLocaleString()}</Text>
          </View>
        );
      })}
      {!loading && !inventory.length ? <View style={styles.empty}><Text style={styles.emptyTitle}>No inventory items</Text><Text style={styles.emptyText}>Inventory added on the desktop app will appear here.</Text></View> : null}
    </ScrollView>
  );
}

function SalesTab({ sales, loading, error, from, to, setFrom, setTo, onLoad }) {
  return (
    <ScrollView contentContainerStyle={styles.tabContent} refreshControl={<RefreshControl refreshing={loading} onRefresh={onLoad} tintColor={GOLD} />}>
      <Text style={styles.pageTitle}>Sales report</Text>
      <Text style={styles.pageHint}>Review sales for any date range</Text>
      <View style={styles.dateFilters}>
        <View style={styles.dateField}><Text style={styles.fieldLabel}>From</Text><TextInput value={from} onChangeText={setFrom} placeholder="YYYY-MM-DD" placeholderTextColor="#786e64" style={styles.dateInput} /></View>
        <View style={styles.dateField}><Text style={styles.fieldLabel}>To</Text><TextInput value={to} onChangeText={setTo} placeholder="YYYY-MM-DD" placeholderTextColor="#786e64" style={styles.dateInput} /></View>
        <Pressable onPress={onLoad} style={styles.filterButton}><Text style={styles.filterButtonText}>Filter</Text></Pressable>
      </View>
      {error ? <Pressable onPress={onLoad} style={styles.error}><Text style={styles.errorTitle}>Connection issue</Text><Text style={styles.errorText}>{error}. Tap to retry.</Text></Pressable> : null}
      <View style={styles.reportMetrics}>
        <View style={[styles.reportMetric, styles.salesMetric]}><Text style={styles.metricLabel}>TOTAL SALES</Text><Text style={styles.salesValue}>{formatMoney(sales.totalSales)}</Text></View>
        <View style={[styles.reportMetric, styles.ordersMetric]}><Text style={styles.metricLabel}>ORDERS</Text><Text style={styles.ordersValue}>{sales.orderCount}</Text></View>
      </View>
      <View style={styles.sectionHeader}><Text style={styles.sectionTitle}>Item sales</Text><Text style={styles.sectionCount}>{from} to {to}</Text></View>
      {sales.items.map((item) => (
        <View key={item.itemName} style={styles.salesRow}><View style={styles.inventoryInfo}><Text style={styles.inventoryName}>{item.itemName}</Text><Text style={styles.inventoryMeta}>Quantity sold: {item.quantity}</Text></View><Text style={styles.orderTotal}>{formatMoney(item.totalSales)}</Text></View>
      ))}
      {!loading && !sales.items.length ? <View style={styles.empty}><Text style={styles.emptyTitle}>No sales for this period</Text><Text style={styles.emptyText}>Try another date range.</Text></View> : null}
    </ScrollView>
  );
}

export default function App() {
  const [activeTab, setActiveTab] = useState('dashboard');
  const [data, setData] = useState({ sales: 0, count: 0, orders: [] });
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');

  const [detailVisible, setDetailVisible] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [detailOrder, setDetailOrder] = useState(null);
  const [inventory, setInventory] = useState([]);
  const [inventoryLoading, setInventoryLoading] = useState(false);
  const [inventoryError, setInventoryError] = useState('');
  const [sales, setSales] = useState({ totalSales: 0, orderCount: 0, items: [] });
  const [salesLoading, setSalesLoading] = useState(false);
  const [salesError, setSalesError] = useState('');
  const [from, setFrom] = useState(today());
  const [to, setTo] = useState(today());

  const loadDashboard = async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    try {
      const date = today();
      const [sales, count, orders] = await Promise.all([
        getJson(`/orders/sales?date=${date}`),
        getJson(`/orders/count?date=${date}`),
        getJson(`/orders?date=${date}`)
      ]);
      await notifyNewOrders(orders);
      setData({ sales: sales.totalSales, count: count.orderCount, orders });
      setError('');
    } catch (requestError) {
      setError(requestError.message || 'Unable to reach the backend');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  const openOrderDetail = async (orderId) => {
    setDetailVisible(true);
    setDetailLoading(true);
    setDetailError('');
    setDetailOrder(null);
    try {
      const order = await getJson(`/orders/${orderId}`);
      setDetailOrder(order);
    } catch (requestError) {
      setDetailError(requestError.message || 'Unable to load order details');
    } finally {
      setDetailLoading(false);
    }
  };

  const loadInventory = async () => {
    setInventoryLoading(true);
    try { setInventory(await getJson('/inventory')); setInventoryError(''); }
    catch (requestError) { setInventoryError(requestError.message || 'Unable to load inventory'); }
    finally { setInventoryLoading(false); }
  };

  const loadSales = async () => {
    setSalesLoading(true);
    try {
      const query = `?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
      const [summary, count, items] = await Promise.all([getJson(`/orders/sales${query}`), getJson(`/orders/count${query}`), getJson(`/orders/item-sales${query}`)]);
      setSales({ totalSales: summary.totalSales, orderCount: count.orderCount, items });
      setSalesError('');
    } catch (requestError) { setSalesError(requestError.message || 'Unable to load sales'); }
    finally { setSalesLoading(false); }
  };

  useEffect(() => {
    if (Platform.OS !== 'web') {
      Notifications.requestPermissionsAsync();
      if (Platform.OS === 'android') Notifications.setNotificationChannelAsync('orders', { name: 'Orders', importance: Notifications.AndroidImportance.HIGH });
    }
    loadDashboard();
    loadInventory();
    loadSales();
    const timer = setInterval(() => loadDashboard(), POLL_MS);
    return () => clearInterval(timer);
  }, []);

  if (loading) return <View style={styles.loading}><ActivityIndicator size="large" color={GOLD} /><Text style={styles.loadingText}>Connecting to BKR Fast Food...</Text></View>;

  const content = activeTab === 'inventory'
    ? <InventoryTab inventory={inventory} loading={inventoryLoading} error={inventoryError} onRefresh={loadInventory} />
    : activeTab === 'sales'
      ? <SalesTab sales={sales} loading={salesLoading} error={salesError} from={from} to={to} setFrom={setFrom} setTo={setTo} onLoad={loadSales} />
      : (
    <FlatList
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => loadDashboard(true)} tintColor={GOLD} />}
      ListHeaderComponent={(
        <>
          <View style={styles.header}>
            <BkrLogoBadge size={58} />
            <View style={styles.headerTitleBox}>
              <Text style={styles.title}>BKR BACHA KHAN</Text>
              <Text style={styles.subtitle}>AND FAST FOOD</Text>
            </View>
            <View style={styles.live}><View style={styles.liveDot} /><Text style={styles.liveText}>LIVE</Text></View>
          </View>
          <Text style={styles.date}>{new Date().toLocaleDateString('en-PK', { weekday: 'long', day: 'numeric', month: 'long' })}</Text>
          {error ? <Pressable onPress={() => loadDashboard(true)} style={styles.error}><Text style={styles.errorTitle}>Connection issue</Text><Text style={styles.errorText}>{error}. Tap to retry.</Text></Pressable> : null}
          <View style={styles.metrics}>
            <View style={[styles.metric, styles.salesMetric]}><Text style={styles.metricLabel}>TOTAL SALES</Text><Text style={styles.salesValue}>{formatMoney(data.sales)}</Text><Text style={styles.metricHint}>Today</Text></View>
            <View style={[styles.metric, styles.ordersMetric]}><Text style={styles.metricLabel}>TOTAL ORDERS</Text><Text style={styles.ordersValue}>{data.count}</Text><Text style={styles.metricHint}>Today</Text></View>
          </View>
          <View style={styles.sectionHeader}><Text style={styles.sectionTitle}>Recent orders</Text><Text style={styles.sectionCount}>{data.orders.length} today  ·  tap for details</Text></View>
        </>
      )}
      data={data.orders}
      keyExtractor={(item) => String(item.orderId)}
      renderItem={({ item }) => (
        <Pressable style={({ pressed }) => [styles.orderRow, pressed && styles.orderRowPressed]} onPress={() => openOrderDetail(item.orderId)}>
          <View style={styles.orderBadge}><Text style={styles.orderBadgeText}>#{item.orderId}</Text></View>
          <View style={styles.orderInfo}><Text style={styles.orderNo}>{item.orderNo || 'Order'}</Text><Text style={styles.orderMeta}>{item.customerName || 'Walk-in'}  ·  {item.orderType || 'Dine In'}</Text></View>
          <Text style={styles.orderTotal}>{formatMoney(item.totalAmount)}</Text>
        </Pressable>
      )}
      ListEmptyComponent={<View style={styles.empty}><Text style={styles.emptyTitle}>No orders yet</Text><Text style={styles.emptyText}>New orders will appear here automatically.</Text></View>}
    />
  );

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar barStyle="light-content" backgroundColor={BLACK} />
      {content}
      <View style={styles.tabBar}>
        {[['dashboard', 'Dashboard'], ['inventory', 'Inventory'], ['sales', 'Sales']].map(([key, label]) => (
          <Pressable key={key} onPress={() => setActiveTab(key)} style={[styles.tabButton, activeTab === key && styles.tabButtonActive]}><Text style={[styles.tabLabel, activeTab === key && styles.tabLabelActive]}>{label}</Text></Pressable>
        ))}
      </View>
      <OrderDetailModal
        visible={detailVisible}
        onClose={() => setDetailVisible(false)}
        loading={detailLoading}
        error={detailError}
        order={detailOrder}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: BLACK },
  content: { padding: 22, paddingBottom: 36 },
  tabContent: { padding: 22, paddingBottom: 100 },
  pageTitle: { color: CREAM, fontSize: 25, fontWeight: '800', marginTop: 12 },
  pageHint: { color: '#978e84', fontSize: 13, marginTop: 6, marginBottom: 20 },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: BLACK },
  loadingText: { marginTop: 14, color: CREAM, fontSize: 15 },

  logoRing: { backgroundColor: GOLD, alignItems: 'center', justifyContent: 'center', borderWidth: 2, borderColor: GOLD_BRIGHT },
  logoDome: { backgroundColor: RED_DARK, alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: GOLD_BRIGHT, overflow: 'hidden' },
  logoBkr: { color: GOLD_BRIGHT, fontWeight: '800', letterSpacing: 1 },
  logoBanner: { position: 'absolute', bottom: 6, backgroundColor: RED, paddingHorizontal: 4, borderRadius: 2 },
  logoBannerText: { color: '#fff', fontSize: 6, fontWeight: '800' },

  header: { flexDirection: 'row', alignItems: 'center', marginTop: 12, gap: 12 },
  headerTitleBox: { flex: 1 },
  title: { color: GOLD_BRIGHT, fontSize: 19, fontWeight: '800', letterSpacing: 0.5 },
  subtitle: { color: RED, fontSize: 12, fontWeight: '800', letterSpacing: 1, marginTop: 2 },
  date: { color: '#a89e90', fontSize: 14, marginTop: 12 },
  live: { flexDirection: 'row', alignItems: 'center', backgroundColor: CARD, paddingHorizontal: 10, paddingVertical: 7, borderRadius: 20, borderWidth: 1, borderColor: '#332c26' },
  liveDot: { width: 7, height: 7, borderRadius: 4, backgroundColor: '#39a96b', marginRight: 6 },
  liveText: { color: '#7fd8a4', fontSize: 11, fontWeight: '800' },
  metrics: { flexDirection: 'row', gap: 12, marginTop: 24 },
  metric: { flex: 1, minHeight: 132, borderRadius: 18, padding: 18, justifyContent: 'space-between', borderWidth: 1 },
  salesMetric: { backgroundColor: RED, borderColor: RED_DARK },
  ordersMetric: { backgroundColor: CARD, borderColor: '#332c26' },
  reportMetrics: { flexDirection: 'row', gap: 12, marginTop: 20 },
  reportMetric: { flex: 1, minHeight: 112, borderRadius: 16, padding: 16, justifyContent: 'space-between', borderWidth: 1 },
  metricLabel: { color: '#fff', opacity: 0.8, fontSize: 11, fontWeight: '800', letterSpacing: 0.8 },
  salesValue: { color: '#fff', fontSize: 23, fontWeight: '800' },
  ordersValue: { color: GOLD_BRIGHT, fontSize: 36, fontWeight: '800' },
  metricHint: { color: '#fff', opacity: 0.7, fontSize: 12 },
  sectionHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 30, marginBottom: 12 },
  sectionTitle: { color: CREAM, fontSize: 20, fontWeight: '800' },
  sectionCount: { color: '#978e84', fontSize: 13 },
  orderRow: { flexDirection: 'row', alignItems: 'center', backgroundColor: CARD, borderRadius: 14, padding: 13, marginBottom: 9, borderWidth: 1, borderColor: '#2a241f' },
  orderRowPressed: { backgroundColor: '#25201b' },
  orderBadge: { width: 42, height: 42, borderRadius: 12, backgroundColor: RED_DARK, alignItems: 'center', justifyContent: 'center' },
  orderBadgeText: { color: GOLD_BRIGHT, fontWeight: '800', fontSize: 12 },
  orderInfo: { flex: 1, marginLeft: 12 },
  orderNo: { color: CREAM, fontSize: 15, fontWeight: '800' },
  orderMeta: { color: '#978e84', fontSize: 12, marginTop: 4 },
  orderTotal: { color: GOLD_BRIGHT, fontSize: 14, fontWeight: '800' },
  inventoryRow: { flexDirection: 'row', alignItems: 'center', backgroundColor: CARD, borderRadius: 14, padding: 15, marginBottom: 9, borderWidth: 1, borderColor: '#2a241f' },
  inventoryRowLow: { borderColor: RED, backgroundColor: '#281715' },
  inventoryInfo: { flex: 1 },
  inventoryName: { color: CREAM, fontSize: 15, fontWeight: '800' },
  inventoryMeta: { color: '#978e84', fontSize: 12, marginTop: 5 },
  inventoryQuantity: { color: GOLD_BRIGHT, fontSize: 22, fontWeight: '800' },
  inventoryQuantityLow: { color: '#ff9a86' },
  salesRow: { flexDirection: 'row', alignItems: 'center', backgroundColor: CARD, borderRadius: 14, padding: 15, marginBottom: 9, borderWidth: 1, borderColor: '#2a241f' },
  dateFilters: { backgroundColor: CARD, borderRadius: 14, padding: 13, borderWidth: 1, borderColor: '#2a241f' },
  dateField: { marginBottom: 10 },
  fieldLabel: { color: '#a89e90', fontSize: 11, fontWeight: '800', marginBottom: 5, textTransform: 'uppercase' },
  dateInput: { color: CREAM, backgroundColor: '#100e0c', borderWidth: 1, borderColor: '#332c26', borderRadius: 8, paddingHorizontal: 11, paddingVertical: 9, fontSize: 14 },
  filterButton: { backgroundColor: RED, borderRadius: 9, paddingVertical: 11, alignItems: 'center', marginTop: 2 },
  filterButtonText: { color: '#fff', fontWeight: '800', fontSize: 14 },
  tabBar: { position: 'absolute', left: 0, right: 0, bottom: 0, flexDirection: 'row', backgroundColor: '#171310', borderTopWidth: 1, borderTopColor: '#332c26', paddingBottom: 8, paddingTop: 7 },
  tabButton: { flex: 1, alignItems: 'center', paddingVertical: 9, marginHorizontal: 5, borderRadius: 9 },
  tabButtonActive: { backgroundColor: RED_DARK },
  tabLabel: { color: '#978e84', fontSize: 12, fontWeight: '700' },
  tabLabelActive: { color: GOLD_BRIGHT },
  empty: { alignItems: 'center', padding: 28, backgroundColor: CARD, borderRadius: 14 },
  emptyTitle: { color: CREAM, fontSize: 16, fontWeight: '800' },
  emptyText: { color: '#978e84', marginTop: 6, fontSize: 13 },
  error: { backgroundColor: '#2a1613', borderRadius: 14, padding: 13, marginTop: 18, borderWidth: 1, borderColor: RED_DARK },
  errorTitle: { color: '#ff9a86', fontWeight: '800' },
  errorText: { color: '#e8b6a8', marginTop: 4, fontSize: 13 },

  modalOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'flex-end' },
  modalCard: { backgroundColor: CARD, borderTopLeftRadius: 22, borderTopRightRadius: 22, padding: 20, maxHeight: '80%' },
  modalHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 },
  modalTitle: { color: GOLD_BRIGHT, fontSize: 17, fontWeight: '800' },
  modalClose: { color: '#e8b6a8', fontWeight: '700' },
  modalMeta: { color: '#a89e90', fontSize: 12, marginTop: 2 },
  modalDivider: { height: 1, backgroundColor: '#332c26', marginVertical: 12 },
  lineRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 10 },
  lineName: { color: CREAM, fontSize: 14, fontWeight: '700' },
  lineQty: { color: '#978e84', fontSize: 12, marginTop: 2 },
  lineTotal: { color: GOLD_BRIGHT, fontSize: 14, fontWeight: '800' },
  totalsRow: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 6 },
  totalsLabel: { color: '#a89e90', fontSize: 13 },
  totalsValue: { color: CREAM, fontSize: 13, fontWeight: '700' },
  grandLabel: { color: CREAM, fontSize: 16, fontWeight: '800', marginTop: 4 },
  grandValue: { color: GOLD_BRIGHT, fontSize: 18, fontWeight: '800', marginTop: 4 }
});
