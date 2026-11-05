package uk.co.mamahelper

import android.app.Application
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID

@Serializable
data class WordNode(
    val id: String,
    val label: String,
    val imagePath: String? = null,
    val children: List<WordNode> = emptyList()
)

data class BoardState(
    val roots: List<WordNode> = emptyList(),
    val path: List<String> = emptyList(),
    val editMode: Boolean = false,
    val typedText: String = "",
    val settingsOpen: Boolean = false
)

class WordRepository(private val app: Application) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(app.filesDir, "words.json")

    fun load(): List<WordNode> {
        if (!file.exists()) {
            app.assets.open("words.json").use { input -> file.outputStream().use(input::copyTo) }
        }
        return json.decodeFromString(file.readText())
    }

    fun save(words: List<WordNode>) {
        file.writeText(json.encodeToString(words))
    }
}

class TalkViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WordRepository(application)
    private val _state = MutableStateFlow(BoardState(roots = repository.load()))
    val state: StateFlow<BoardState> = _state.asStateFlow()
    private var lastTap = 0L

    fun acceptTap(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTap < 400) return false
        lastTap = now
        return true
    }

    fun currentNodes(): List<WordNode> = findChildren(_state.value.roots, _state.value.path)
    fun currentPathNodes(): List<WordNode> {
        val nodes = mutableListOf<WordNode>()
        var level = _state.value.roots
        _state.value.path.forEach { id ->
            level.firstOrNull { it.id == id }?.let { node -> nodes += node; level = node.children }
        }
        return nodes
    }

    fun open(node: WordNode) { _state.value = _state.value.copy(path = _state.value.path + node.id) }
    fun back() {
        _state.value = _state.value.copy(path = _state.value.path.dropLast(1))
    }
    fun home() { _state.value = _state.value.copy(path = emptyList(), typedText = "") }
    fun jump(index: Int) { _state.value = _state.value.copy(path = _state.value.path.take(index + 1)) }
    fun setTyped(text: String) { _state.value = _state.value.copy(typedText = text) }
    fun setEdit(enabled: Boolean) { _state.value = _state.value.copy(editMode = enabled) }
    fun setSettings(open: Boolean) { _state.value = _state.value.copy(settingsOpen = open) }

    fun add(label: String) = updateTree { children ->
        children + WordNode(UUID.randomUUID().toString(), label.trim(), children = emptyList())
    }
    fun addChild(parent: WordNode, label: String) = replace(_state.value.roots, parent.id) {
        it.copy(children = it.children + WordNode(UUID.randomUUID().toString(), label.trim()))
    }
    fun rename(node: WordNode, label: String) = replace(_state.value.roots, node.id) { it.copy(label = label.trim()) }
    fun delete(node: WordNode) = replace(_state.value.roots, node.id) { null }
    fun move(node: WordNode, direction: Int) {
        val children = currentNodes().toMutableList()
        val index = children.indexOfFirst { it.id == node.id }
        val target = index + direction
        if (index >= 0 && target in children.indices) {
            val item = children.removeAt(index)
            children.add(target, item)
            updateTree { children }
        }
    }

    private fun updateTree(transform: (List<WordNode>) -> List<WordNode>) {
        val state = _state.value
        val roots = if (state.path.isEmpty()) transform(state.roots) else replaceAtPath(state.roots, state.path, transform)
        repository.save(roots)
        _state.value = state.copy(roots = roots)
    }
    private fun replace(nodes: List<WordNode>, id: String, transform: (WordNode) -> WordNode?) {
        fun recurse(items: List<WordNode>): List<WordNode> = items.mapNotNull { item ->
            when {
                item.id == id -> transform(item)
                else -> item.copy(children = recurse(item.children))
            }
        }
        val roots = recurse(_state.value.roots)
        repository.save(roots)
        _state.value = _state.value.copy(roots = roots)
    }
    private fun replaceAtPath(nodes: List<WordNode>, path: List<String>, transform: (List<WordNode>) -> List<WordNode>): List<WordNode> {
        val id = path.first()
        return nodes.map { node ->
            if (node.id != id) node else if (path.size == 1) node.copy(children = transform(node.children))
            else node.copy(children = replaceAtPath(node.children, path.drop(1), transform))
        }
    }
    private fun findChildren(nodes: List<WordNode>, path: List<String>): List<WordNode> {
        if (path.isEmpty()) return nodes
        val node = nodes.firstOrNull { it.id == path.first() } ?: return emptyList()
        return findChildren(node.children, path.drop(1))
    }
}

class Speaker(app: Application) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(app, this)
    private var ready = false
    var rate = 0.9f
    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.UK
            tts.setSpeechRate(rate)
        }
    }
    fun speak(text: String, urgent: Boolean = false) {
        if (!ready) return
        tts.setSpeechRate(if (urgent) 0.9f else rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "talk-board")
    }
    fun shutdown() = tts.shutdown()
}

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speaker = Speaker(application)
        setContent {
            MaterialTheme { TalkBoard(speaker) }
        }
    }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }
}

@Composable
private fun TalkBoard(speaker: Speaker, viewModel: TalkViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    BackHandler(enabled = state.path.isNotEmpty()) { viewModel.back() }
    if (state.settingsOpen) SettingsDialog(onClose = { viewModel.setSettings(false) })
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Breadcrumbs(viewModel)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { viewModel.setSettings(true) }) {
                    Icon(Icons.Default.Settings, "Open edit settings")
                }
            }
        },
        bottomBar = { BottomBar(speaker, viewModel) }
    ) { padding ->
        val current = viewModel.currentPathNodes().lastOrNull()
        if (current?.id == "letters") LetterBoard(
            text = state.typedText,
            onText = viewModel::setTyped,
            onSpeak = { speaker.speak(state.typedText) },
            modifier = Modifier.padding(padding)
        ) else WordGrid(
            nodes = viewModel.currentNodes(),
            state = state,
            onSpeak = { word -> if (viewModel.acceptTap()) speaker.speak(word) },
            onOpen = viewModel::open,
            onRename = viewModel::rename,
            onDelete = viewModel::delete,
            onMove = viewModel::move,
            onAdd = viewModel::add,
            onAddChild = viewModel::addChild,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun Breadcrumbs(viewModel: TalkViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Home", fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.home() })
        viewModel.currentPathNodes().forEachIndexed { index, node ->
            Icon(Icons.Default.ChevronRight, null)
            Text(node.label, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.jump(index) })
        }
    }
}

@Composable
private fun WordGrid(
    nodes: List<WordNode>,
    state: BoardState,
    onSpeak: (String) -> Unit,
    onOpen: (WordNode) -> Unit,
    onRename: (WordNode, String) -> Unit,
    onDelete: (WordNode) -> Unit,
    onMove: (WordNode, Int) -> Unit,
    onAdd: (String) -> Unit,
    onAddChild: (WordNode, String) -> Unit,
    modifier: Modifier
) {
    var adding by remember { mutableStateOf(false) }
    var childParent by remember { mutableStateOf<WordNode?>(null) }
    val columns = if (LocalConfiguration.current.screenWidthDp >= 700) 4 else 3
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            text = state.path.lastOrNull()?.let { id -> state.roots.findNode(id)?.label } ?: "Home",
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(nodes, key = { it.id }) { node ->
                WordButton(
                    node = node,
                    editing = state.editMode,
                    shade = colourFor(state.path.firstOrNull() ?: node.id),
                    onSpeak = onSpeak,
                    onOpen = onOpen,
                    onRename = onRename,
                    onDelete = onDelete,
                    onMove = onMove,
                    onAddChild = { childParent = node }
                )
            }
            if (state.editMode) item {
                Button(onClick = { adding = true }, modifier = Modifier.size(148.dp)) {
                    Icon(Icons.Default.Add, "Add word"); Spacer(Modifier.width(4.dp)); Text("Add here")
                }
            }
        }
    }
    if (adding) WordDialog("Add word", "", { value -> onAdd(value); adding = false }, { adding = false })
    childParent?.let { parent ->
        WordDialog("Add inside ${parent.label}", "", { value ->
            onAddChild(parent, value)
            childParent = null
        }, { childParent = null })
    }
}

@Composable
private fun WordButton(
    node: WordNode,
    editing: Boolean,
    shade: Color,
    onSpeak: (String) -> Unit,
    onOpen: (WordNode) -> Unit,
    onRename: (WordNode, String) -> Unit,
    onDelete: (WordNode) -> Unit,
    onMove: (WordNode, Int) -> Unit,
    onAddChild: () -> Unit
) {
    var renaming by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = Modifier.size(148.dp),
        colors = CardDefaults.cardColors(containerColor = shade),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(if (node.children.isEmpty()) 1f else 2f)
                        .fillMaxSize()
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSpeak(node.label)
                        }
                        .padding(8.dp)
                ) {
                    Text(
                        node.label,
                        color = Color.White,
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
                if (node.children.isNotEmpty()) Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.weight(1f).fillMaxSize().background(shade.copy(alpha = .7f))
                        .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onOpen(node) }
                ) { Icon(Icons.Default.ChevronRight, "Open ${node.label}", tint = Color.White, modifier = Modifier.size(42.dp)) }
            }
            if (editing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename ${node.label}", tint = Color.White) }
                IconButton(onClick = onAddChild) { Icon(Icons.Default.Add, "Add inside ${node.label}", tint = Color.White) }
                IconButton(onClick = { onMove(node, -1) }) { Text("Up", color = Color.White) }
                IconButton(onClick = { onMove(node, 1) }) { Text("Down", color = Color.White) }
                IconButton(onClick = { onDelete(node) }) { Icon(Icons.Default.Delete, "Delete ${node.label}", tint = Color.White) }
            }
        }
    }
    if (renaming) WordDialog("Rename word", node.label, { value -> onRename(node, value); renaming = false }, { renaming = false })
}

@Composable
private fun BottomBar(speaker: Speaker, viewModel: TalkViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BottomButton("Yes", Color(0xFF197B30)) { speaker.speak("Yes") }
        BottomButton("No", Color(0xFFB3261E)) { speaker.speak("No") }
        BottomButton("Help", Color(0xFFFF0000)) { speaker.speak("Help Help", true) }
        BottomButton("Back", Color(0xFF37474F)) { viewModel.back() }
        BottomButton("Home", Color(0xFF37474F)) { viewModel.home() }
    }
}

@Composable
private fun RowScope.BottomButton(label: String, colour: Color, action: () -> Unit) {
    Button(
        onClick = action, colors = ButtonDefaults.buttonColors(containerColor = colour),
        modifier = Modifier.weight(1f).height(62.dp)
    ) { Text(label, fontSize = 18.sp, textAlign = TextAlign.Center) }
}

@Composable
private fun LetterBoard(text: String, onText: (String) -> Unit, onSpeak: () -> Unit, modifier: Modifier) {
    val keys = ('A'..'Z').map { it.toString() }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(text, fontSize = 32.sp, modifier = Modifier.fillMaxWidth().height(64.dp), textAlign = TextAlign.Center)
        LazyVerticalGrid(columns = GridCells.Fixed(5), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(keys) { key -> Button(onClick = { onText(text + key) }, modifier = Modifier.height(64.dp)) { Text(key, fontSize = 24.sp) } }
            item { Button(onClick = { onText("$text ") }, modifier = Modifier.height(64.dp)) { Text("Space") } }
            item { Button(onClick = { onText(text.dropLast(1)) }, modifier = Modifier.height(64.dp)) { Text("Delete") } }
            item { Button(onClick = onSpeak, modifier = Modifier.height(64.dp)) { Text("Speak") } }
        }
    }
}

@Composable
private fun WordDialog(title: String, initial: String, confirm: (String) -> Unit, dismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text("Single word") }) },
        confirmButton = { TextButton(onClick = { if (value.trim().isNotEmpty() && !value.contains(" ")) confirm(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsDialog(onClose: () -> Unit, viewModel: TalkViewModel = viewModel()) {
    var pin by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (state.editMode) "Edit mode" else "Unlock edit mode") },
        text = {
            Column {
                if (!state.editMode) {
                    OutlinedTextField(pin, { pin = it.take(4) }, label = { Text("4-digit PIN") })
                } else {
                    Text("Add, rename, delete and reorder words on the board.")
                    Spacer(Modifier.height(8.dp))
                    Text("Photo, backup and voice settings are kept locally.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!state.editMode && pin == "1234") viewModel.setEdit(true)
                if (state.editMode) viewModel.setEdit(false)
                onClose()
            }) { Text(if (state.editMode) "Finish editing" else "Unlock") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

private fun colourFor(id: String): Color {
    val colours = listOf(0xFF005F73, 0xFF6A4C93, 0xFF9B2226, 0xFF386641, 0xFF7F5539, 0xFF3A506B)
    return Color(colours[(id.hashCode().toUInt().toInt() and Int.MAX_VALUE) % colours.size])
}

private fun List<WordNode>.findNode(id: String): WordNode? {
    for (node in this) {
        if (node.id == id) return node
        node.children.findNode(id)?.let { return it }
    }
    return null
}
