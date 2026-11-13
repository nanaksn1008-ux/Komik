package com.example.komikreader

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URLEncoder

// ===================== Model =====================
data class Source(
    val name: String, val type: String, val baseUrl: String, val list: String,
    val search: String, val itemSel: String, val titleSel: String, val linkSel: String,
    val coverSel: String, val chapterSel: String, val pageSel: String
)
data class Comic(val title: String, val url: String, val cover: String, val src: Source)
data class Chapter(val title: String, val url: String)

sealed interface Screen {
    data object Home : Screen
    data object Repo : Screen
    data class Browse(val type: String) : Screen
    data class Detail(val comic: Comic) : Screen
    data class Reader(val comic: Comic, val chapters: List<Chapter>, val index: Int) : Screen
}

// ===================== Jaringan & parsing =====================
val http = OkHttpClient()
const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

suspend fun fetchText(url: String, ref: String? = null): String = withContext(Dispatchers.IO) {
    val b = Request.Builder().url(url).header("User-Agent", UA)
    if (ref != null) b.header("Referer", ref)
    http.newCall(b.build()).execute().use { it.body?.string() ?: "" }
}

suspend fun fetchHtml(url: String, ref: String? = null): Document = Jsoup.parse(fetchText(url, ref), url)

fun JSONObject.s(k: String, d: String = ""): String = optString(k, d)

fun parseRepo(json: String): List<Source> {
    val a = JSONObject(json).getJSONArray("sources")
    return (0 until a.length()).map {
        val x = a.getJSONObject(it)
        Source(
            x.s("name"), x.s("type").lowercase(), x.s("baseUrl").trimEnd('/'), x.s("list"),
            x.s("search"), x.s("itemSel"), x.s("titleSel", "a@title"), x.s("linkSel", "a@href"),
            x.s("coverSel", "img@src"), x.s("chapterSel"), x.s("pageSel")
        )
    }
}

fun Element.attrVal(at: String): String = when (at) {
    "text" -> text().trim()
    "href" -> absUrl("href")
    "src" -> listOf("data-src", "data-lazy-src", "data-original", "src")
        .map { absUrl(it) }.firstOrNull { it.isNotEmpty() } ?: ""
    else -> attr(at)
}

fun Element.pick(sel: String): String {
    val p = sel.split("@", limit = 2)
    val css = p[0].trim()
    val e = if (css.isEmpty()) this else selectFirst(css) ?: return ""
    return e.attrVal(p.getOrElse(1) { "text" })
}

suspend fun loadComics(s: Source, query: String): List<Comic> {
    val path = if (query.isNotBlank() && s.search.isNotBlank())
        s.search.replace("{q}", URLEncoder.encode(query, "UTF-8")) else s.list
    val doc = fetchHtml(s.baseUrl + path.replace("{p}", "1"), s.baseUrl)
    return doc.select(s.itemSel).mapNotNull {
        val link = it.pick(s.linkSel)
        if (link.isEmpty()) null
        else Comic(it.pick(s.titleSel).ifEmpty { it.text() }, link, it.pick(s.coverSel), s)
    }
}

// ===================== Penyimpanan =====================
fun Context.prefs() = getSharedPreferences("komik", 0)
fun Context.repos(): List<String> {
    val a = JSONArray(prefs().getString("repos", "[]"))
    return (0 until a.length()).map { a.getString(it) }
}
fun Context.saveRepos(l: List<String>) = prefs().edit().putString("repos", JSONArray(l).toString()).apply()

// ===================== Activity =====================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        sources = ctx.repos().flatMap { runCatching { parseRepo(fetchText(it)) }.getOrDefault(emptyList()) }
    }
    BackHandler(screen !is Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Reader -> Screen.Detail(s.comic)
            is Screen.Detail -> Screen.Browse(s.comic.src.type)
            else -> Screen.Home
        }
    }
    when (val s = screen) {
        Screen.Home -> Home(sources, { screen = Screen.Browse(it) }, { screen = Screen.Repo })
        Screen.Repo -> RepoScreen(sources) { reload++ }
        is Screen.Browse -> Browse(s.type, sources) { screen = Screen.Detail(it) }
        is Screen.Detail -> Detail(s.comic) { list, i -> screen = Screen.Reader(s.comic, list, i) }
        is Screen.Reader -> Reader(s.comic, s.chapters, s.index)
    }
}

// ===================== Menu utama =====================
@Composable
fun Home(sources: List<Source>, onOpen: (String) -> Unit, onRepo: () -> Unit) {
    val ctx = LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    val bg = File(ctx.filesDir, "bg.img")
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            ctx.contentResolver.openInputStream(uri)?.use { i -> bg.outputStream().use { o -> i.copyTo(o) } }
            ver++
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF12121A))) {
        if (bg.exists()) {
            key(ver) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(bg)
                        .diskCacheKey("bg$ver").memoryCacheKey("bg$ver").build(),
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Komik Reader", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("${sources.size} sumber dimuat", color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(24.dp))
            listOf("manhwa" to "Manhwa", "manhua" to "Manhua", "manga" to "Manga").forEach { (k, n) ->
                Card(
                    onClick = { onOpen(k) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(88.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x99000000))
                ) {
                    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.CenterStart) {
                        Text(n, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { pick.launch("image/*") }) { Text("Ganti Background") }
                OutlinedButton(onClick = onRepo) { Text("Repo") }
            }
        }
    }
}

// ===================== Repo =====================
@Composable
fun RepoScreen(sources: List<Source>, onChange: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(ctx.repos()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Repo", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(url, { url = it }, label = { Text("URL repo (file JSON)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (url.isNotBlank()) { list = list + url.trim(); ctx.saveRepos(list); url = ""; onChange() }
        }) { Text("Tambah") }
        LazyColumn(Modifier.weight(1f)) {
            items(list) { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r, Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = { list = list - r; ctx.saveRepos(list); onChange() }) { Text("Hapus") }
                }
            }
        }
        Text("Sumber aktif: " + sources.joinToString { "${it.name} (${it.type})" })
    }
}

// ===================== Daftar komik =====================
@Composable
fun Img(url: String, ref: String, mod: Modifier, scale: ContentScale) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(url).addHeader("Referer", ref).crossfade(true).build(),
        contentDescription = null, modifier = mod, contentScale = scale
    )
}

@Composable
fun Browse(type: String, sources: List<Source>, onOpen: (Comic) -> Unit) {
    var q by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var comics by remember { mutableStateOf<List<Comic>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    LaunchedEffect(sources, query) {
        loading = true; err = ""
        val r = coroutineScope {
            sources.filter { it.type == type }.map { s ->
                async {
                    runCatching { loadComics(s, query) }
                        .onFailure { e -> err = "${s.name}: ${e.message}" }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        comics = r.flatten(); loading = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("Cari $type") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { query = q }) { Text("Cari") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (err.isNotEmpty()) Text(err, color = Color(0xFFFF6B6B), modifier = Modifier.padding(8.dp))
        if (!loading && comics.isEmpty()) Text("Belum ada komik. Tambahkan repo dulu di menu utama.", Modifier.padding(16.dp))
        LazyVerticalGrid(GridCells.Fixed(3), contentPadding = PaddingValues(8.dp)) {
            items(comics) { c ->
                Column(Modifier.padding(4.dp).clickable { onOpen(c) }) {
                    Img(c.cover, c.src.baseUrl,
                        Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)), ContentScale.Crop)
                    Text(c.title, maxLines = 2, fontSize = 12.sp)
                }
            }
        }
    }
}

// ===================== Detail & bab =====================
@Composable
fun Detail(c: Comic, onRead: (List<Chapter>, Int) -> Unit) {
    var ch by remember { mutableStateOf<List<Chapter>?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(c) {
        runCatching {
            fetchHtml(c.url, c.src.baseUrl).select(c.src.chapterSel)
                .map { Chapter(it.text().trim(), it.absUrl("href")) }.filter { it.url.isNotEmpty() }
        }.onSuccess { ch = it }.onFailure { err = it.message ?: "Gagal memuat" }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp)) {
            Img(c.cover, c.src.baseUrl, Modifier.width(100.dp).aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)), ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(c.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${c.src.name} • ${c.src.type}")
            }
        }
        val list = ch
        when {
            err.isNotEmpty() -> Text(err, Modifier.padding(16.dp), color = Color(0xFFFF6B6B))
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> LazyColumn {
                itemsIndexed(list) { i, x ->
                    Text(x.title, Modifier.fillMaxWidth().clickable { onRead(list, i) }.padding(16.dp))
                    HorizontalDivider()
                }
            }
        }
    }
}

// ===================== Pembaca =====================
@Composable
fun EdgeTap(mod: Modifier, onTap: () -> Unit) {
    Box(mod.fillMaxHeight().clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap))
}

@Composable
fun Reader(c: Comic, chapters: List<Chapter>, start: Int) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(start) }
    var pages by remember { mutableStateOf<List<String>?>(null) }
    var rtl by remember { mutableStateOf(ctx.prefs().getBoolean("rtl", true)) }
    val webtoon = c.src.type != "manga" // manhwa/manhua = webtoon, manga = buku

    LaunchedEffect(idx) {
        pages = null
        pages = runCatching {
            val p = c.src.pageSel.split("@", limit = 2)
            fetchHtml(chapters[idx].url, c.src.baseUrl).select(p[0])
                .map { it.attrVal(p.getOrElse(1) { "src" }) }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val pg = pages
        when {
            pg == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            pg.isEmpty() -> Text("Halaman tidak ditemukan", Modifier.align(Alignment.Center))
            else -> key(idx) {
                if (webtoon) Webtoon(pg, c.src.baseUrl, idx > 0) { idx-- }
                else MangaPager(pg, c.src.baseUrl, rtl)
            }
        }
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹ Bab", Modifier.clickable { if (idx < chapters.size - 1) idx++ }.padding(end = 12.dp), color = Color.White)
            Text(chapters[idx].title, Modifier.weight(1f), maxLines = 1, color = Color.White, fontSize = 13.sp)
            if (!webtoon) {
                Text(if (rtl) "Kanan→Kiri" else "Kiri→Kanan", Modifier.clickable {
                    rtl = !rtl; ctx.prefs().edit().putBoolean("rtl", rtl).apply()
                }.padding(horizontal = 8.dp), color = Color(0xFF90CAF9), fontSize = 12.sp)
            }
            Text("Bab ›", Modifier.clickable { if (idx > 0) idx-- }.padding(start = 12.dp), color = Color.White)
        }
    }
}

@Composable
fun Webtoon(pages: List<String>, ref: String, hasNext: Boolean, onNext: () -> Unit) {
    val st = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val h = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = st, modifier = Modifier.fillMaxSize()) {
            items(pages) { Img(it, ref, Modifier.fillMaxWidth().heightIn(min = 200.dp), ContentScale.FillWidth) }
            if (hasNext) item {
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().padding(24.dp)) { Text("Bab berikutnya") }
            }
        }
        // tepi kiri & kanan: tap = gulir ke bawah
        Row(Modifier.fillMaxSize()) {
            EdgeTap(Modifier.weight(0.2f)) { scope.launch { st.animateScrollBy(h * 0.85f) } }
            Spacer(Modifier.weight(0.6f))
            EdgeTap(Modifier.weight(0.2f)) { scope.launch { st.animateScrollBy(h * 0.85f) } }
        }
    }
}

@Composable
fun MangaPager(pages: List<String>, ref: String, rtl: Boolean) {
    val st = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    fun go(d: Int) { scope.launch { st.animateScrollToPage((st.currentPage + d).coerceIn(0, pages.lastIndex)) } }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = st, modifier = Modifier.fillMaxSize(), reverseLayout = rtl) { i ->
            Img(pages[i], ref, Modifier.fillMaxSize(), ContentScale.Fit)
        }
        Row(Modifier.fillMaxSize()) {
            EdgeTap(Modifier.weight(0.2f)) { go(if (rtl) 1 else -1) }   // tepi kiri
            Spacer(Modifier.weight(0.6f))
            EdgeTap(Modifier.weight(0.2f)) { go(if (rtl) -1 else 1) }   // tepi kanan
        }
        Text("${st.currentPage + 1}/${pages.size}",
            Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White, fontSize = 12.sp)
    }
}
