package io.joryio.sdk.ecommerce

import io.joryio.sdk.Joryio

/**
 * Represents a product for e-commerce tracking
 */
data class EcommerceProduct(
    val productId: String,
    val name: String,
    val price: Double,
    val quantity: Int? = null,
    val category: String? = null,
    val brand: String? = null,
    val variant: String? = null,
    val variantId: String? = null,
    val sku: String? = null,
    val imageUrl: String? = null,
    val url: String? = null
) {
    fun toProperties(): Map<String, Any?> = buildMap {
        put("product_id", productId)
        put("name", name)
        put("price", price)
        quantity?.let { put("quantity", it) }
        category?.let { put("category", it) }
        brand?.let { put("brand", it) }
        variant?.let { put("variant", it) }
        variantId?.let { put("variant_id", it) }
        sku?.let { put("sku", it) }
        imageUrl?.let { put("image_url", it) }
        url?.let { put("url", it) }
    }
}

/**
 * Represents a cart item (product with quantity)
 */
data class EcommerceCartItem(
    val productId: String,
    val name: String,
    val price: Double,
    val quantity: Int,
    val category: String? = null,
    val brand: String? = null,
    val variant: String? = null,
    val variantId: String? = null,
    val sku: String? = null,
    val imageUrl: String? = null,
    val url: String? = null
) {
    val itemTotal: Double get() = price * quantity

    fun toProduct(): EcommerceProduct = EcommerceProduct(
        productId = productId,
        name = name,
        price = price,
        quantity = quantity,
        category = category,
        brand = brand,
        variant = variant,
        variantId = variantId,
        sku = sku,
        imageUrl = imageUrl,
        url = url
    )

    fun toProperties(): Map<String, Any?> = buildMap {
        put("product_id", productId)
        put("name", name)
        put("price", price)
        put("quantity", quantity)
        category?.let { put("category", it) }
        brand?.let { put("brand", it) }
        variant?.let { put("variant", it) }
        variantId?.let { put("variant_id", it) }
        sku?.let { put("sku", it) }
        imageUrl?.let { put("image_url", it) }
        url?.let { put("url", it) }
    }
}

/**
 * Represents an order
 */
data class EcommerceOrder(
    val orderId: String,
    val items: List<EcommerceCartItem>,
    val value: Double,
    val currency: String? = null,
    val shipping: Double? = null,
    val tax: Double? = null,
    val discount: Double? = null,
    val coupon: String? = null
) {
    // filterNotNull: a Java or bridge caller can put a null in the list.
    val itemCount: Int get() = items.filterNotNull().sumOf { it.quantity }
}

/**
 * Configuration for e-commerce tracking.
 *
 * `trackPageViews` and `trackAddToCart` were removed: nothing read them, so
 * setting either did nothing (shared SDK contract item 11 - every option
 * works or is removed).
 */
data class EcommerceConfig(
    val currency: String = "USD",
)

/**
 * E-Commerce tracking helper class
 * Provides standardized e-commerce event tracking
 *
 * Usable before initialize() (contract item 12): built without an instance -
 * [create], or a tracker held in a field initialiser - it sends through the
 * static `Joryio.track`, which buffers until the SDK is ready. The default
 * used to be `Joryio.getInstance()`, which THROWS before initialize(), so
 * `EcommerceTracker.create()` in an Application field crashed the app.
 *
 * A required argument that is missing - a null from Java or a bridge, a
 * blank id, an amount that is not a finite number - is logged and nothing is
 * sent (contract item 42): `purchase` with no order id used to record an
 * "Order Completed" that revenue reports then counted. No method throws.
 *
 * ONE set on every Joryio SDK and wrapper (pinned by the backend's
 * ecommerce-guard-parity spec): a product - and every cart/order line - needs
 * a productId and a finite price; cart calls a finite cartValue; purchase an
 * orderId and a finite value (the items may be empty); every other id or
 * label is required; an optional amount may be left out, but one given must
 * be finite - it is refused, not silently dropped.
 */
class EcommerceTracker @JvmOverloads constructor(
    private val sdk: Joryio? = null,
    private val config: EcommerceConfig = EcommerceConfig()
) {

    /**
     * A null from a Java or bridge caller where a value is required: warn and
     * do nothing (shared SDK contract item 19). Kotlin's own null check used
     * to throw a NullPointerException into the host app instead.
     */
    private fun warnNull(method: String, what: String) {
        android.util.Log.w("Joryio", "ecommerce.$method() needs $what; ignored")
    }

    /** A product the tracker can describe: an id and a finite price. */
    private fun validProduct(method: String, productId: String?, price: Double): Boolean {
        if (productId.isNullOrBlank()) {
            warnNull(method, "a productId")
            return false
        }
        if (!price.isFinite()) {
            warnNull(method, "a finite price")
            return false
        }
        return true
    }

    /** An optional amount: absent is fine, present must be finite. */
    private fun validOptionalAmount(method: String, name: String, value: Double?): Boolean =
        value == null || validAmount(method, name, value)

    private fun validAmount(method: String, name: String, value: Double?): Boolean {
        if (value == null || !value.isFinite()) {
            warnNull(method, "a finite $name")
            return false
        }
        return true
    }

    private fun validItems(method: String, items: List<EcommerceCartItem>?): Boolean {
        if (items == null) {
            warnNull(method, "items")
            return false
        }
        if (items.any { (it as EcommerceCartItem?) == null }) {
            android.util.Log.w("Joryio", "ecommerce.$method(): null line(s) in items skipped")
        }
        @Suppress("NAME_SHADOWING")
        val items = lines(items)
        return items.all { validProduct(method, it.productId, it.price) }
    }

    /**
     * The lines that exist. A Java or bridge caller can put a `null` in the
     * list; it is SKIPPED, the same as the React Native and Unity wrappers do
     * (scan 5, Android NICE 5). It used to throw a NullPointerException into
     * the host app from outside send()'s guard.
     */
    private fun lines(items: List<EcommerceCartItem>): List<EcommerceCartItem> = items.filterNotNull()

    /** One way out, never throwing into the host app. */
    private fun send(eventName: String, properties: Map<String, Any?>) {
        try {
            val target = sdk
            if (target != null) target.track(eventName, properties) else Joryio.track(eventName, properties)
        } catch (t: Throwable) {
            android.util.Log.w("Joryio", "ecommerce event '$eventName' not tracked: ${t.message}")
        }
    }

    private fun itemsData(items: List<EcommerceCartItem>) = lines(items).map { item ->
        buildMap<String, Any?> {
            put("product_id", item.productId)
            put("name", item.name)
            put("price", item.price)
            put("quantity", item.quantity)
            item.variantId?.let { put("variant_id", it) }
            item.sku?.let { put("sku", it) }
        }
    }

    // MARK: - Product Events

    /**
     * Track when a user views a product
     */
    fun viewProduct(product: EcommerceProduct?) {
        if (product == null) return warnNull("viewProduct", "product")
        if (!validProduct("viewProduct", product.productId, product.price)) return
        val props = product.toProperties().toMutableMap()
        props["currency"] = config.currency
        send("Product Viewed", props)
    }

    /**
     * Track when a user views a category/collection page
     */
    @JvmOverloads
    fun viewCategory(
        categoryId: String?,
        categoryName: String?,
        properties: Map<String, Any?>? = emptyMap()
    ) {
        if (categoryId.isNullOrBlank() || categoryName.isNullOrBlank()) {
            return warnNull("viewCategory", "categoryId/categoryName")
        }
        val props = mutableMapOf<String, Any?>(
            "category_id" to categoryId,
            "category_name" to categoryName
        )
        props.putAll(properties.orEmpty())
        send("Product List Viewed", props)
    }

    /**
     * Track when a user searches for products
     */
    @JvmOverloads
    fun search(
        query: String?,
        resultCount: Int? = null,
        properties: Map<String, Any?>? = emptyMap()
    ) {
        if (query.isNullOrBlank()) return warnNull("search", "query")
        val props = mutableMapOf<String, Any?>("query" to query)
        resultCount?.let { props["result_count"] = it }
        props.putAll(properties.orEmpty())
        send("Products Searched", props)
    }

    // MARK: - Cart Events

    /**
     * Track when a user adds an item to cart
     */
    @JvmOverloads
    fun addToCart(item: EcommerceCartItem?, cartValue: Double? = null) {
        if (item == null) return warnNull("addToCart", "item")
        if (!validProduct("addToCart", item.productId, item.price)) return
        if (!validOptionalAmount("addToCart", "cartValue", cartValue)) return
        val props = item.toProperties().toMutableMap()
        props["item_total"] = item.itemTotal
        props["currency"] = config.currency
        cartValue?.let { props["cart_value"] = it }
        send("Product Added", props)
    }

    /**
     * Track when a user removes an item from cart
     */
    @JvmOverloads
    fun removeFromCart(product: EcommerceProduct?, quantity: Int = 1) {
        if (product == null) return warnNull("removeFromCart", "product")
        if (!validProduct("removeFromCart", product.productId, product.price)) return
        val props = mutableMapOf<String, Any?>(
            "product_id" to product.productId,
            "name" to product.name,
            "price" to product.price,
            "quantity" to quantity,
            "currency" to config.currency
        )
        product.variantId?.let { props["variant_id"] = it }
        product.sku?.let { props["sku"] = it }
        send("Product Removed", props)
    }

    /**
     * Track the cart being viewed.
     *
     * `Cart Viewed` has been canonical all along, but no tracker had a method
     * for it - so the only way to send it was to type the name by hand, which
     * is the habit these trackers exist to remove. Same payload as
     * [updateCart]: the cart is the same thing either way.
     */
    fun cartViewed(items: List<EcommerceCartItem>?, cartValue: Double) {
        if (!validItems("cartViewed", items) || !validAmount("cartViewed", "cartValue", cartValue)) return
        send("Cart Viewed", mapOf(
            "items" to itemsData(items!!),
            "item_count" to lines(items).sumOf { it.quantity },
            "cart_value" to cartValue,
            "currency" to config.currency
        ))
    }

    /**
     * Track when a user updates their cart
     */
    fun updateCart(items: List<EcommerceCartItem>?, cartValue: Double) {
        if (!validItems("updateCart", items) || !validAmount("updateCart", "cartValue", cartValue)) return
        send("Cart Updated", mapOf(
            "items" to itemsData(items!!),
            "item_count" to lines(items).sumOf { it.quantity },
            "cart_value" to cartValue,
            "currency" to config.currency
        ))
    }

    // MARK: - Checkout Events

    /**
     * Track when a user starts checkout
     */
    @JvmOverloads
    fun startCheckout(
        items: List<EcommerceCartItem>?,
        cartValue: Double,
        properties: Map<String, Any?>? = emptyMap()
    ) {
        if (!validItems("startCheckout", items) || !validAmount("startCheckout", "cartValue", cartValue)) return
        val props = mutableMapOf<String, Any?>(
            "items" to itemsData(items!!),
            "item_count" to lines(items).sumOf { it.quantity },
            "value" to cartValue,
            "currency" to config.currency
        )
        props.putAll(properties.orEmpty())
        send("Checkout Started", props)
    }

    /**
     * Track when a user adds payment info
     */
    @JvmOverloads
    fun addPaymentInfo(paymentMethod: String?, properties: Map<String, Any?>? = emptyMap()) {
        if (paymentMethod.isNullOrBlank()) return warnNull("addPaymentInfo", "paymentMethod")
        val props = mutableMapOf<String, Any?>("payment_method" to paymentMethod)
        props.putAll(properties.orEmpty())
        send("Payment Info Entered", props)
    }

    // MARK: - Order Events

    /**
     * Track when an order is placed.
     *
     * `orderId` (not blank), `value` (a finite number) and the items are
     * required; a purchase missing one is ignored with a warning rather than
     * recorded as a zero order (contract item 42).
     */
    fun purchase(order: EcommerceOrder?) {
        if (order == null) return warnNull("purchase", "order")
        if (order.orderId.isNullOrBlank()) return warnNull("purchase", "an orderId")
        if (!validAmount("purchase", "value", order.value)) return
        if (!validItems("purchase", order.items)) return
        if (!validOptionalAmount("purchase", "shipping", order.shipping) ||
            !validOptionalAmount("purchase", "tax", order.tax) ||
            !validOptionalAmount("purchase", "discount", order.discount)
        ) return
        val itemsData = lines(order.items).map { item ->
            buildMap<String, Any?> {
                put("product_id", item.productId)
                put("name", item.name)
                put("price", item.price)
                put("quantity", item.quantity)
                item.category?.let { put("category", it) }
                item.brand?.let { put("brand", it) }
                item.variantId?.let { put("variant_id", it) }
                item.sku?.let { put("sku", it) }
            }
        }

        val props = mutableMapOf<String, Any?>(
            // `total` and `orderId`, NOT `value` and `order_id`.
            //
            // The revenue pipeline reads exactly these two keys - see
            // purchase-vocabulary.ts, which says of `value`/`revenue`: "somebody
            // else's convention", and of `order_id`: "a key nothing reads".
            // Every purchase tracked through this tracker therefore landed as
            // ZERO revenue with no order id for attribution, on all three SDKs.
            //
            // Not a crash, not a warning: a $0 order that looks like an order.
            // The demo app hit the same thing and its comment already warned
            // about it - the SDK it was warning about never got fixed.
            "orderId" to order.orderId,
            "items" to itemsData,
            "item_count" to order.itemCount,
            "total" to order.value,
            "currency" to (order.currency ?: config.currency)
        )
        order.shipping?.let { props["shipping"] = it }
        order.tax?.let { props["tax"] = it }
        order.discount?.let { props["discount"] = it }
        order.coupon?.let { props["coupon"] = it }

        send("Order Completed", props)
    }

    /**
     * Track order fulfillment (shipped)
     */
    @JvmOverloads
    fun orderFulfilled(orderId: String?, trackingNumber: String? = null, carrier: String? = null) {
        if (orderId.isNullOrBlank()) return warnNull("orderFulfilled", "orderId")
        val props = mutableMapOf<String, Any?>("order_id" to orderId)
        trackingNumber?.let { props["tracking_number"] = it }
        carrier?.let { props["carrier"] = it }
        send("Order Fulfilled", props)
    }

    /**
     * Track order delivery
     */
    fun orderDelivered(orderId: String?) {
        if (orderId.isNullOrBlank()) return warnNull("orderDelivered", "orderId")
        send("Order Delivered", mapOf("order_id" to orderId))
    }

    /**
     * Track order cancellation
     */
    @JvmOverloads
    fun orderCancelled(orderId: String?, reason: String? = null) {
        if (orderId.isNullOrBlank()) return warnNull("orderCancelled", "orderId")
        val props = mutableMapOf<String, Any?>("order_id" to orderId)
        reason?.let { props["reason"] = it }
        send("Order Cancelled", props)
    }

    /**
     * Track order refund
     */
    @JvmOverloads
    fun orderRefunded(orderId: String?, refundAmount: Double? = null, reason: String? = null) {
        if (orderId.isNullOrBlank()) return warnNull("orderRefunded", "orderId")
        if (!validOptionalAmount("orderRefunded", "refundAmount", refundAmount)) return
        val props = mutableMapOf<String, Any?>("order_id" to orderId)
        refundAmount?.let { props["refund_amount"] = it }
        reason?.let { props["reason"] = it }
        send("Order Refunded", props)
    }

    // MARK: - Wishlist Events

    /**
     * Track product added to wishlist
     */
    fun addToWishlist(product: EcommerceProduct?) {
        if (product == null) return warnNull("addToWishlist", "product")
        if (!validProduct("addToWishlist", product.productId, product.price)) return
        val props = mutableMapOf<String, Any?>(
            "product_id" to product.productId,
            "name" to product.name,
            "price" to product.price,
            "currency" to config.currency
        )
        product.category?.let { props["category"] = it }
        product.brand?.let { props["brand"] = it }
        send("Product Added to Wishlist", props)
    }

    /**
     * Track product removed from wishlist
     */
    fun removeFromWishlist(product: EcommerceProduct?) {
        if (product == null) return warnNull("removeFromWishlist", "product")
        if (!validProduct("removeFromWishlist", product.productId, product.price)) return
        send("Product Removed from Wishlist", mapOf(
            "product_id" to product.productId,
            "name" to product.name
        ))
    }

    // MARK: - Engagement Events

    /**
     * Track product shared
     */
    fun shareProduct(product: EcommerceProduct?, shareMethod: String?) {
        if (product == null || shareMethod.isNullOrBlank()) return warnNull("shareProduct", "product/shareMethod")
        if (!validProduct("shareProduct", product.productId, product.price)) return
        send("Product Shared", mapOf(
            "product_id" to product.productId,
            "name" to product.name,
            "share_method" to shareMethod
        ))
    }

    /**
     * Track coupon applied
     */
    @JvmOverloads
    fun applyCoupon(couponCode: String?, discountAmount: Double? = null, discountType: String? = null) {
        if (couponCode.isNullOrBlank()) return warnNull("applyCoupon", "couponCode")
        if (!validOptionalAmount("applyCoupon", "discountAmount", discountAmount)) return
        val props = mutableMapOf<String, Any?>("coupon_code" to couponCode)
        discountAmount?.let { props["discount_amount"] = it }
        discountType?.let { props["discount_type"] = it }
        send("Coupon Applied", props)
    }

    /**
     * Track coupon removed
     */
    fun removeCoupon(couponCode: String?) {
        if (couponCode.isNullOrBlank()) return warnNull("removeCoupon", "couponCode")
        send("Coupon Removed", mapOf("coupon_code" to couponCode))
    }

    /**
     * Track product review submitted
     */
    @JvmOverloads
    fun submitReview(productId: String?, rating: Int, reviewText: String? = null) {
        if (productId.isNullOrBlank()) return warnNull("submitReview", "productId")
        val props = mutableMapOf<String, Any?>(
            "product_id" to productId,
            "rating" to rating
        )
        reviewText?.let { props["review_text"] = it }
        send("Product Reviewed", props)
    }

    companion object {
        /**
         * Create an e-commerce tracker. Safe before initialize(): its events
         * are buffered until the SDK is ready (it used to throw).
         */
        @JvmStatic
        @JvmOverloads
        fun create(config: EcommerceConfig? = EcommerceConfig()): EcommerceTracker =
            EcommerceTracker(null, config ?: EcommerceConfig())
    }
}
