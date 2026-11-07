package model;

public class PurchaseHistoryItem {
    private final int itemId;
    private final String itemName;

    public PurchaseHistoryItem(int itemId, String itemName) {
        this.itemId = itemId;
        this.itemName = itemName;
    }

    public int getItemId() {
        return itemId;
    }

    public String getItemName() {
        return itemName;
    }

    @Override
    public String toString() {
        return itemName;
    }
}
