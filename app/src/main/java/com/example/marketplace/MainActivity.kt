package com.example.marketplace
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.room.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.storage
import coil.compose.AsyncImage
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import com.example.marketplace.ui.theme.MyApplicationTheme
import java.util.Locale
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// Animation State
class CartAnimationState(val scope: CoroutineScope) {
    var animationData by mutableStateOf<AnimationData?>(null)

    data class AnimationData(
        val start: Offset,
        val product: Product
    )

    fun trigger(start: Offset, product: Product) {
        animationData = AnimationData(start, product)
    }
}

val LocalCartAnimation = staticCompositionLocalOf<CartAnimationState> { error("No CartAnimationState provided") }
val LocalCartPosition = compositionLocalOf { mutableStateOf(Offset.Zero) }

@Composable
fun CartFlyingAnimation(state: CartAnimationState, targetOffset: Offset) {
    val data = state.animationData ?: return
    
    val animatable = remember { Animatable(data.start, Offset.VectorConverter) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(data) {
        animatable.snapTo(data.start)
        alpha.snapTo(1f)
        
        launch {
            animatable.animateTo(
                targetValue = targetOffset,
                animationSpec = spring(
                    dampingRatio = 0.7f,
                    stiffness = 300f
                )
            )
        }
        launch {
            delay(400)
            alpha.animateTo(0f, tween(300))
            state.animationData = null
        }
    }

    if (alpha.value > 0f) {
        Box(
            modifier = Modifier
                .offset { IntOffset(animatable.value.x.roundToInt(), animatable.value.y.roundToInt()) }
                .size(40.dp)
                .alpha(alpha.value)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.ShoppingCart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// Models
data class Product(
    val id: Int,
    val title: String,
    val price: Double,
    val description: String = "No description available.",
    val imageUrl: String? = null,
    val color: Color = Color.Gray
)
data class Promotion(val id: Int, val title: String, val subtitle: String, val tag: String)

// Room Database
@Entity(tableName = "cart_items")
data class CartItemEntity(
    @PrimaryKey val productId: Int,
    val title: String,
    val price: Double,
    val description: String,
    val imageUrl: String?,
    val colorValue: Long, // Storing color as Long
    val quantity: Int = 1
)

@Dao
interface CartDao {
    @Query("SELECT * FROM cart_items")
    fun getAllCartItems(): kotlinx.coroutines.flow.Flow<List<CartItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cartItem: CartItemEntity)

    @Update
    suspend fun update(cartItem: CartItemEntity)

    @Delete
    suspend fun delete(cartItem: CartItemEntity)

    @Query("DELETE FROM cart_items WHERE productId = :productId")
    suspend fun deleteByProductId(productId: Int)

    @Query("DELETE FROM cart_items")
    suspend fun clearCart()
}

@Database(entities = [CartItemEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cartDao(): CartDao
}

// ViewModel
class MarketplaceViewModel(private val cartDao: CartDao) : ViewModel() {
    private val firestore = Firebase.firestore
    private val storage = Firebase.storage
    private val productsCollection = firestore.collection("products")

    private val _promotions = MutableStateFlow(
        listOf(
            Promotion(1, "Spring Sale 50% Off", "On all electronic accessories", "Limited Offer"),
            Promotion(2, "Summer Savings", "On outdoor gear", "Hot Deal")
        )
    )
    val promotions: StateFlow<List<Promotion>> = _promotions

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    init {
        // Observe products from Firestore
        productsCollection.addSnapshotListener { snapshot, e ->
            if (e != null) return@addSnapshotListener
            val productList = snapshot?.documents?.mapNotNull { doc ->
                val id = doc.getLong("id")?.toInt() ?: 0
                val title = doc.getString("title") ?: ""
                val price = doc.getDouble("price") ?: 0.0
                val description = doc.getString("description") ?: ""
                val imageUrl = doc.getString("imageUrl")
                val colorValue = doc.getLong("colorValue") ?: 0xFFf3f4f9L
                Product(id, title, price, description, imageUrl, Color(colorValue.toULong()))
            } ?: emptyList()
            _products.value = productList.ifEmpty { getMockProducts() }
        }
    }

    private fun getMockProducts() = listOf(
        Product(1, "Wireless Buds Pro", 59.99, "High-quality wireless earbuds with noise cancellation.", null, Color(0xFFf3f4f9)),
        Product(2, "Smart Watch S3", 129.00, "Advanced smart watch with health tracking and GPS.", null, Color(0xFFf3f4f9)),
        Product(3, "Mechanical Keyboard", 85.50, "Tactile mechanical keyboard with RGB lighting.", null, Color(0xFFf3f4f9)),
        Product(4, "Leather Laptop Bag", 110.00, "Premium leather bag for laptops up to 15 inches.", null, Color(0xFFf3f4f9))
    )

    val cartItems: StateFlow<List<Product>> = cartDao.getAllCartItems()
        .map { entities ->
            entities.map { entity ->
                Product(
                    id = entity.productId,
                    title = entity.title,
                    price = entity.price,
                    description = entity.description,
                    imageUrl = entity.imageUrl,
                    color = Color(entity.colorValue.toULong())
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun addProduct(title: String, price: Double, imageUri: String? = null, color: Color = Color(0xFFf3f4f9)) {
        viewModelScope.launch {
            var finalImageUrl: String? = null
            
            // Upload image to Firebase Storage if exists
            if (imageUri != null) {
                try {
                    val fileName = "products/${UUID.randomUUID()}.jpg"
                    val fileUri = Uri.parse(imageUri)
                    val ref = storage.reference.child(fileName)
                    ref.putFile(fileUri).await()
                    finalImageUrl = ref.downloadUrl.await().toString()
                } catch (e: Exception) {
                    // Handle upload error
                }
            }

            val newId = (_products.value.maxOfOrNull { it.id } ?: 0) + 1
            val productData = hashMapOf(
                "id" to newId,
                "title" to title,
                "price" to price,
                "description" to "Added via Admin Panel",
                "imageUrl" to finalImageUrl,
                "colorValue" to color.value.toLong()
            )
            
            productsCollection.document(newId.toString()).set(productData)
        }
    }

    fun deleteProduct(productId: Int) {
        viewModelScope.launch {
            productsCollection.document(productId.toString()).delete()
            cartDao.deleteByProductId(productId)
        }
    }

    fun addToCart(product: Product) {
        viewModelScope.launch {
            cartDao.insert(
                CartItemEntity(
                    productId = product.id,
                    title = product.title,
                    price = product.price,
                    description = product.description,
                    imageUrl = product.imageUrl,
                    colorValue = product.color.value.toLong()
                )
            )
        }
    }

    fun removeFromCart(product: Product) {
        viewModelScope.launch {
            cartDao.deleteByProductId(product.id)
        }
    }

    fun getCartTotal(): Double {
        return cartItems.value.sumOf { it.price }
    }
}

class AuthViewModel : ViewModel() {
    private val auth: FirebaseAuth = Firebase.auth
    
    var currentUser by mutableStateOf(auth.currentUser)
        private set

    var offlineUserEmail by mutableStateOf<String?>(null)
        private set

    var isFirebaseEnabled by mutableStateOf(true)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var avatarUrl by mutableStateOf<String?>(null)
        private set

    private val storage = Firebase.storage
    private val firestore = Firebase.firestore

    init {
        auth.addAuthStateListener {
            if (isFirebaseEnabled) {
                currentUser = it.currentUser
                loadAvatar()
            }
        }
    }

    private fun loadAvatar() {
        currentUser?.uid?.let { uid ->
            firestore.collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    avatarUrl = doc.getString("avatarUrl")
                }
        }
    }

    fun updateAvatar(imageUri: String) {
        if (!isFirebaseEnabled) {
            avatarUrl = imageUri
            return
        }

        val uid = currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                val fileName = "avatars/$uid.jpg"
                val ref = storage.reference.child(fileName)
                ref.putFile(Uri.parse(imageUri)).await()
                val url = ref.downloadUrl.await().toString()
                
                firestore.collection("users").document(uid)
                    .set(mapOf("avatarUrl" to url), SetOptions.merge())
                    .await()
                
                avatarUrl = url
            } catch (e: Exception) {}
        }
    }

    fun toggleFirebase(enabled: Boolean) {
        isFirebaseEnabled = enabled
        if (!enabled) {
            currentUser = null
        } else {
            offlineUserEmail = null
            currentUser = auth.currentUser
        }
    }

    fun signIn(email: String, password: String, onSuccess: () -> Unit) {
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Please fill all fields"
            return
        }

        if (!isFirebaseEnabled) {
            offlineUserEmail = email
            onSuccess()
            return
        }

        isLoading = true
        errorMessage = null
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                isLoading = false
                if (task.isSuccessful) {
                    onSuccess()
                } else {
                    errorMessage = task.exception?.localizedMessage ?: "Login failed"
                }
            }
    }

    fun signUp(email: String, password: String, onSuccess: () -> Unit) {
        if (email.isBlank() || password.isBlank()) {
            errorMessage = "Please fill all fields"
            return
        }

        if (!isFirebaseEnabled) {
            offlineUserEmail = email
            onSuccess()
            return
        }

        isLoading = true
        errorMessage = null
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                isLoading = false
                if (task.isSuccessful) {
                    onSuccess()
                } else {
                    errorMessage = task.exception?.localizedMessage ?: "Registration failed"
                }
            }
    }

    fun signOut() {
        if (isFirebaseEnabled) {
            auth.signOut()
        }
        offlineUserEmail = null
        currentUser = null
    }
    
    fun clearError() {
        errorMessage = null
    }
}

enum class ThemeMode { Light, Dark, System }

class SettingsViewModel : ViewModel() {
    var themeMode by mutableStateOf(ThemeMode.System)
    var accentColor by mutableStateOf(Color(0xFF6750A4))
    
    var notificationsEnabled by mutableStateOf(true)
    var selectedLanguage by mutableStateOf("English")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "marketplace-db"
        ).build()
        val cartDao = database.cartDao()

        enableEdgeToEdge()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val darkTheme = when (settingsViewModel.themeMode) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            MyApplicationTheme(
                darkTheme = darkTheme,
                primaryColor = settingsViewModel.accentColor
            ) {
                val viewModel: MarketplaceViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return MarketplaceViewModel(cartDao) as T
                        }
                    }
                )
                val authViewModel: AuthViewModel = viewModel()
                MarketplaceApp(viewModel, authViewModel, settingsViewModel)
            }
        }
    }
}

@Composable
fun MarketplaceApp(viewModel: MarketplaceViewModel, authViewModel: AuthViewModel, settingsViewModel: SettingsViewModel) {
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()
    val cartAnimationState = remember { CartAnimationState(coroutineScope) }
    val cartPosition = remember { mutableStateOf(Offset.Zero) }

    val items = listOf(
        Screen.Home,
        Screen.Catalog,
        Screen.Profile
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val startDestination = if (authViewModel.currentUser != null || authViewModel.offlineUserEmail != null) "main_tabs" else Screen.Login.route

    val onNavigate: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(
        LocalCartAnimation provides cartAnimationState,
        LocalCartPosition provides cartPosition
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                val currentRoute = currentDestination?.route
                if (currentRoute != Screen.Login.route && currentRoute != Screen.Register.route) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 0.dp,
                    ) {
                        items.forEach { screen ->
                            val isSelected = (currentRoute == "main_tabs" && 
                                             ((screen == Screen.Home && (navController.currentBackStackEntry?.savedStateHandle?.get<Int>("pager_index") ?: 0) == 0) ||
                                              (screen == Screen.Catalog && navController.currentBackStackEntry?.savedStateHandle?.get<Int>("pager_index") == 1) ||
                                              (screen == Screen.Profile && navController.currentBackStackEntry?.savedStateHandle?.get<Int>("pager_index") == 2)))
                                             || currentRoute == screen.route

                            NavigationBarItem(
                                icon = { 
                                    AnimatedContent(targetState = screen.icon, label = "icon") { icon ->
                                        Icon(icon, contentDescription = screen.title) 
                                    }
                                },
                                label = { Text(screen.title, fontWeight = FontWeight.Bold) },
                                selected = isSelected,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                onClick = {
                                    if (screen in items) {
                                        navController.navigate("main_tabs") {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                        // Handle pager index via savedStateHandle or direct state if possible
                                        navController.currentBackStackEntry?.savedStateHandle?.set("pager_index", items.indexOf(screen))
                                    } else {
                                        onNavigate(screen.route)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding),
                enterTransition = { 
                    val from = initialState.destination.route
                    val to = targetState.destination.route
                    val fromIndex = getRouteIndex(from)
                    val toIndex = getRouteIndex(to)
                    
                    if (fromIndex != -1 && toIndex != -1) {
                        if (toIndex > fromIndex) {
                            slideInHorizontally(animationSpec = tween(400)) { it } + fadeIn(animationSpec = tween(400))
                        } else {
                            slideInHorizontally(animationSpec = tween(400)) { -it } + fadeIn(animationSpec = tween(400))
                        }
                    } else {
                        fadeIn(animationSpec = tween(400))
                    }
                },
                exitTransition = { 
                    val from = initialState.destination.route
                    val to = targetState.destination.route
                    val fromIndex = getRouteIndex(from)
                    val toIndex = getRouteIndex(to)

                    if (fromIndex != -1 && toIndex != -1) {
                        if (toIndex > fromIndex) {
                            slideOutHorizontally(animationSpec = tween(400)) { -it } + fadeOut(animationSpec = tween(400))
                        } else {
                            slideOutHorizontally(animationSpec = tween(400)) { it } + fadeOut(animationSpec = tween(400))
                        }
                    } else {
                        fadeOut(animationSpec = tween(400))
                    }
                }
            ) {
                composable(Screen.Login.route) {
                    LoginScreen(
                        viewModel = authViewModel,
                        onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                        onLoginSuccess = {
                            navController.navigate("main_tabs") {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Screen.Register.route) {
                    RegistrationScreen(
                        viewModel = authViewModel,
                        onNavigateToLogin = { navController.popBackStack() },
                        onRegisterSuccess = {
                            navController.navigate("main_tabs") {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        }
                    )
                }
                composable("main_tabs") { backStackEntry ->
                    val pagerIndex = backStackEntry.savedStateHandle.getStateFlow("pager_index", 0).collectAsState()
                    MainTabsScreen(
                        initialPage = pagerIndex.value,
                        viewModel = viewModel,
                        authViewModel = authViewModel,
                        onNavigateToAdmin = { navController.navigate(Screen.Admin.route) },
                        onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                        onNavigateToCart = { navController.navigate(Screen.Cart.route) },
                        onNavigateToProductDetail = { productId ->
                            navController.navigate(Screen.ProductDetail.createRoute(productId))
                        },
                        onLogout = {
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onPageChanged = { index ->
                            backStackEntry.savedStateHandle.set("pager_index", index)
                        }
                    )
                }
                composable(Screen.Admin.route) { AdminScreen(viewModel) { navController.popBackStack() } }
                composable(Screen.Cart.route) { CartScreen(viewModel) { navController.popBackStack() } }
                composable(Screen.ProductDetail.route) { backStackEntry ->
                    val productId = backStackEntry.arguments?.getString("productId")?.toIntOrNull()
                    ProductDetailScreen(productId, viewModel, { navController.popBackStack() }, { navController.navigate(Screen.Cart.route) })
                }
                composable(Screen.Settings.route) { SettingsScreen(settingsViewModel, viewModel) { navController.popBackStack() } }
            }
        }
        CartFlyingAnimation(state = cartAnimationState, targetOffset = cartPosition.value)
    }
}

private fun getRouteIndex(route: String?): Int {
    return when(route) {
        Screen.Login.route -> 0
        Screen.Register.route -> 1
        "main_tabs" -> 2
        Screen.Cart.route -> 3
        Screen.Admin.route -> 4
        Screen.Settings.route -> 5
        else -> -1
    }
}

@Composable
fun MainTabsScreen(
    initialPage: Int,
    viewModel: MarketplaceViewModel,
    authViewModel: AuthViewModel,
    onNavigateToAdmin: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCart: () -> Unit,
    onNavigateToProductDetail: (Int) -> Unit,
    onLogout: () -> Unit,
    onPageChanged: (Int) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { 3 }
    
    LaunchedEffect(initialPage) {
        pagerState.animateScrollToPage(initialPage)
    }

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> HomeScreen(viewModel, authViewModel, onNavigateToAdmin, { onPageChanged(2) }, onNavigateToCart, onNavigateToProductDetail)
            1 -> CatalogScreen()
            2 -> ProfileScreen(authViewModel, onNavigateToSettings, onLogout)
        }
    }
}

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Login : Screen("login", "Login", Icons.Default.LockOpen)
    object Register : Screen("register", "Register", Icons.Default.PersonAdd)
    object Home : Screen("home", "Home", Icons.Filled.Home)
    object Catalog : Screen("catalog", "Catalog", Icons.Filled.ShoppingCart)
    object Profile : Screen("profile", "Profile", Icons.Filled.Person)
    object Admin : Screen("admin", "Admin", Icons.Filled.Person)
    object Cart : Screen("cart", "Cart", Icons.Filled.ShoppingCart)
    object ProductDetail : Screen("product_detail/{productId}", "Detail", Icons.Filled.Info) {
        fun createRoute(productId: Int) = "product_detail/$productId"
    }
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun HomeScreen(
    viewModel: MarketplaceViewModel,
    authViewModel: AuthViewModel,
    onNavigateToAdmin: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToCart: () -> Unit,
    onNavigateToProductDetail: (Int) -> Unit
) {
    val promotions by viewModel.promotions.collectAsState()
    val products by viewModel.products.collectAsState()
    val cartItems by viewModel.cartItems.collectAsState()
    val userEmail = authViewModel.currentUser?.email ?: authViewModel.offlineUserEmail

    var searchQuery by remember { mutableStateOf("") }
    
    val filteredProducts = remember(products, searchQuery) {
        if (searchQuery.isEmpty() || searchQuery == "mrwadmin") {
            products
        } else {
            products.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery == "mrwadmin") {
            onNavigateToAdmin()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Custom Search Bar Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(28.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(12.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                decorationBox = { innerTextField ->
                    if (searchQuery.isEmpty()) {
                        Text("Search products...", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    innerTextField()
                }
            )
            
            // Cart Icon
            val cartPosition = LocalCartPosition.current
            BadgedBox(
                badge = {
                    if (cartItems.isNotEmpty()) {
                        Badge { Text(cartItems.size.toString()) }
                    }
                },
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        cartPosition.value = coordinates.positionInWindow()
                    }
                    .clickable { onNavigateToCart() }
            ) {
                Icon(Icons.Default.ShoppingCart, contentDescription = "Cart", tint = MaterialTheme.colorScheme.primary)
            }
            
            Spacer(modifier = Modifier.width(16.dp))

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { onNavigateToProfile() },
                contentAlignment = Alignment.Center
            ) {
                if (authViewModel.avatarUrl != null) {
                    AsyncImage(
                        model = authViewModel.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = userEmail?.take(2)?.uppercase() ?: "JD",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Promotions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Promotions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            TextButton(
                onClick = { /* TODO */ },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = "See all",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(promotions) { promo ->
                Box(
                    modifier = Modifier
                        .size(width = 280.dp, height = 128.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.primary
                                )
                            )
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = promo.tag.uppercase(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.alpha(0.9f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = promo.title,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = promo.subtitle,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.alpha(0.8f)
                        )
                    }
                }
            }
        }

        // Popular Products
        Text(
            text = "Popular Products",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filteredProducts) { product ->
                val cartAnimation = LocalCartAnimation.current
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + expandVertically()
                ) {
                    ProductCard(
                        product = product,
                        onClick = { onNavigateToProductDetail(product.id) },
                        onQuickAdd = { startPos ->
                            viewModel.addToCart(product)
                            cartAnimation.trigger(startPos, product)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ProductCard(product: Product, onClick: () -> Unit, onQuickAdd: (Offset) -> Unit = {}) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 200f),
        label = "scale"
    )

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var cardPosition by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { cardPosition = it.positionInWindow() }
            .pointerInput(Unit) {
                val velocityTracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity().x
                        scope.launch {
                            if (offsetX.value > 80f || velocity > 400f) {
                                offsetX.animateTo(100f, spring(dampingRatio = 0.8f, stiffness = 400f))
                            } else {
                                offsetX.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f))
                            }
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch {
                            offsetX.snapTo((offsetX.value + dragAmount).coerceIn(0f, 150f))
                        }
                    }
                )
            }
    ) {
        // Quick Add Background Action
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { 
                    onQuickAdd(cardPosition)
                    scope.launch { offsetX.animateTo(0f) }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                Icons.Default.AddShoppingCart,
                contentDescription = "Quick Add",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 24.dp).size(28.dp)
            )
        }

        Card(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .clip(RoundedCornerShape(16.dp))
                .clickable { 
                    if (offsetX.value > 0f) {
                        scope.launch { offsetX.animateTo(0f) }
                    } else {
                        onClick()
                    }
                },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Image Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(product.color),
                    contentAlignment = Alignment.Center
                ) {
                    if (product.imageUrl != null) {
                        AsyncImage(
                            model = product.imageUrl,
                            contentDescription = product.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("📷", fontSize = 24.sp, modifier = Modifier.alpha(0.6f))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = product.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$${product.price}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun CatalogScreen() {
    val categories = listOf(
        "Electronics" to Icons.Default.Devices,
        "Fashion" to Icons.Default.Checkroom,
        "Home" to Icons.Default.HomeWork,
        "Beauty" to Icons.Default.Face,
        "Sports" to Icons.Default.SportsBasketball,
        "Toys" to Icons.Default.SmartToy
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Categories",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(categories) { (name, icon) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, contentDescription = name, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(viewModel: AuthViewModel, onNavigateToSettings: () -> Unit, onLogout: () -> Unit) {
    val userEmail = viewModel.currentUser?.email ?: viewModel.offlineUserEmail
    val isOffline = viewModel.offlineUserEmail != null
    val avatar = viewModel.avatarUrl
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.updateAvatar(it.toString()) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        // Avatar
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { launcher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (avatar != null) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(userEmail?.take(2)?.uppercase() ?: "JD", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            
            // Edit Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Text("User Profile ${if (isOffline) "(Offline)" else ""}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(userEmail ?: "Not signed in", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(32.dp))

        // Settings items
        val settings = listOf(
            "My Orders" to Icons.AutoMirrored.Filled.List,
            "Shipping Address" to Icons.Default.LocationOn,
            "Payment Methods" to Icons.Default.Payment,
            "Notifications" to Icons.Default.Notifications,
            "Settings" to Icons.Default.Settings
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(settings) { (name, icon) ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { 
                        if (name == "Settings") onNavigateToSettings()
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = name, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { 
                viewModel.signOut()
                onLogout()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Logout", fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(viewModel: AuthViewModel, onNavigateToRegister: () -> Unit, onLoginSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Login", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Firebase", style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = viewModel.isFirebaseEnabled,
                            onCheckedChange = { viewModel.toggleFirebase(it) },
                            modifier = Modifier.scale(0.7f)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Welcome Back", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                text = if (viewModel.isFirebaseEnabled) "Sign in to your account" else "Offline mode active (Bypass Auth)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (viewModel.isFirebaseEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            
            if (viewModel.errorMessage != null && viewModel.isFirebaseEnabled) {
                Text(viewModel.errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { viewModel.signIn(email, password, onLoginSuccess) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !viewModel.isLoading
            ) {
                if (viewModel.isLoading && viewModel.isFirebaseEnabled) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (viewModel.isFirebaseEnabled) "Sign In" else "Offline Login", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            TextButton(onClick = onNavigateToRegister, modifier = Modifier.padding(top = 16.dp)) {
                Text("Don't have an account? Register here")
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose { viewModel.clearError() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationScreen(viewModel: AuthViewModel, onNavigateToLogin: () -> Unit, onRegisterSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Register", style = MaterialTheme.typography.titleMedium) },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Firebase", style = MaterialTheme.typography.labelSmall)
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = viewModel.isFirebaseEnabled,
                            onCheckedChange = { viewModel.toggleFirebase(it) },
                            modifier = Modifier.scale(0.7f)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Create Account", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(
                text = if (viewModel.isFirebaseEnabled) "Join our marketplace today" else "Offline mode active",
                style = MaterialTheme.typography.bodyMedium,
                color = if (viewModel.isFirebaseEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirm Password") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            
            if (viewModel.errorMessage != null && viewModel.isFirebaseEnabled) {
                Text(viewModel.errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { 
                    if (password == confirmPassword) {
                        viewModel.signUp(email, password, onRegisterSuccess)
                    } else {
                        // Pass mismatch handle
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !viewModel.isLoading
            ) {
                if (viewModel.isLoading && viewModel.isFirebaseEnabled) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (viewModel.isFirebaseEnabled) "Register" else "Offline Register", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            TextButton(onClick = onNavigateToLogin, modifier = Modifier.padding(top = 16.dp)) {
                Text("Already have an account? Login here")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearError() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(productId: Int?, viewModel: MarketplaceViewModel, onBack: () -> Unit, onGoToCart: () -> Unit) {
    val products by viewModel.products.collectAsState()
    val product = products.find { it.id == productId }
    val cartAnimation = LocalCartAnimation.current
    var buttonPosition by remember { mutableStateOf(Offset.Zero) }

    if (product == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Product not found")
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Product Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onGoToCart) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = "Cart")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(product.color),
                contentAlignment = Alignment.Center
            ) {
                if (product.imageUrl != null) {
                    AsyncImage(
                        model = product.imageUrl,
                        contentDescription = product.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("📷", fontSize = 64.sp, modifier = Modifier.alpha(0.6f))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = product.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(text = "$${product.price}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Description", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(text = product.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { 
                    viewModel.addToCart(product)
                    cartAnimation.trigger(buttonPosition, product)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { buttonPosition = it.positionInWindow() },
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                Icon(Icons.Default.AddShoppingCart, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add to Cart", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(viewModel: MarketplaceViewModel, onBack: () -> Unit) {
    val cartItems by viewModel.cartItems.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shopping Cart") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (cartItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Your cart is empty", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cartItems) { product ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(product.color),
                                contentAlignment = Alignment.Center
                            ) {
                                if (product.imageUrl != null) {
                                    AsyncImage(model = product.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Text("📷", fontSize = 20.sp)
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(product.title, fontWeight = FontWeight.Bold)
                                Text("$${product.price}", color = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { viewModel.removeFromCart(product) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total:", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("$${String.format(Locale.getDefault(), "%.2f", viewModel.getCartTotal())}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { /* Checkout Logic */ },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Text("Checkout", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminScreen(viewModel: MarketplaceViewModel, onBack: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(Color(0xFFEADDFF)) }
    var selectedImageUri by remember { mutableStateOf<String?>(null) }
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        selectedImageUri = uri?.toString()
    }
    
    val products by viewModel.products.collectAsState()

    val colors = listOf(
        Color(0xFFEADDFF), Color(0xFFE8DEF8), Color(0xFFFFD8E4),
        Color(0xFFD0BCFF), Color(0xFFCCC2DC), Color(0xFFEFB8C8)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Admin Panel", style = MaterialTheme.typography.headlineMedium)
        
        // Image Picker
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(selectedColor)
                .clickable { launcher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (selectedImageUri != null) {
                AsyncImage(
                    model = selectedImageUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(32.dp))
                    Text("Add Photo", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Product Title") },
            modifier = Modifier.fillMaxWidth()
        )
        
        OutlinedTextField(
            value = price,
            onValueChange = { price = it },
            label = { Text("Product Price") },
            modifier = Modifier.fillMaxWidth()
        )

        Text("Select Card Color", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            if (selectedColor == color) 2.dp else 0.dp,
                            MaterialTheme.colorScheme.primary,
                            CircleShape
                        )
                        .clickable { selectedColor = color }
                )
            }
        }
        
        Button(
            onClick = {
                val priceDouble = price.toDoubleOrNull() ?: 0.0
                if (title.isNotEmpty()) {
                    viewModel.addProduct(title, priceDouble, selectedImageUri, selectedColor)
                    title = ""
                    price = ""
                    selectedImageUri = null
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Product")
        }
        
        HorizontalDivider()
        
        Text("Current Products", style = MaterialTheme.typography.titleMedium)
        
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(products) { product ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(product.color),
                            contentAlignment = Alignment.Center
                        ) {
                            if (product.imageUrl != null) {
                                AsyncImage(
                                    model = product.imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.Gray)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(product.title, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("$${product.price}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { viewModel.deleteProduct(product.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, marketplaceViewModel: MarketplaceViewModel, onBack: () -> Unit) {
    val accentColors = listOf(
        Color(0xFF6750A4) to "Purple",
        Color(0xFF2196F3) to "Blue",
        Color(0xFF4CAF50) to "Green",
        Color(0xFFF44336) to "Red",
        Color(0xFFFF9800) to "Orange",
        Color(0xFFE91E63) to "Pink",
        Color(0xFF00BCD4) to "Cyan",
        Color(0xFF009688) to "Teal",
        Color(0xFFFFC107) to "Amber",
        Color(0xFF3F51B5) to "Indigo"
    )
    
    var devClickCount by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Theme Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            val isSelected = viewModel.themeMode == mode
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.themeMode = mode },
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(mode.name, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }

            // Accent Color Section
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Accent Color", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(accentColors) { (color, _) ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (viewModel.accentColor == color) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable { viewModel.accentColor = color },
                            contentAlignment = Alignment.Center
                        ) {
                            if (viewModel.accentColor == color) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                            }
                        }
                    }
                }
            }

            // Other Settings
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App Preferences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Notifications")
                            }
                            Switch(checked = viewModel.notificationsEnabled, onCheckedChange = { viewModel.notificationsEnabled = it })
                        }
                    }
                }
            }

            // Info Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Version")
                            Text("1.2.0-dev-early-alpha", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { 
                                    devClickCount++
                                    if (devClickCount >= 5) {
                                        marketplaceViewModel.addToCart(
                                            Product(999, "Farrux ez bot", 67.0, "Legendary Easter Egg", null, Color.Magenta)
                                        )
                                        devClickCount = 0
                                    }
                                }
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Developer")
                            Text("mrw228 & isoqov", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
