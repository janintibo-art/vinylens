package com.vinylens.discogs

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private enum class Side { FRONT, BACK }

    /** Pochette imprimée, ou étiquettes du disque (white label, pochette générique). */
    private enum class Mode { SLEEVE, DISC }

    private lateinit var imgFront: ImageView
    private lateinit var imgBack: ImageView
    private lateinit var lblFront: TextView
    private lateinit var lblBack: TextView
    private lateinit var queryInput: EditText
    private lateinit var catnoInput: EditText
    private lateinit var chips: ChipGroup
    private lateinit var vinylOnly: MaterialCheckBox
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var cardFront: MaterialCardView
    private lateinit var cardBack: MaterialCardView
    private lateinit var titleFront: TextView
    private lateinit var titleBack: TextView
    private lateinit var modeGroup: MaterialButtonToggleGroup

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("vinylens", Context.MODE_PRIVATE)
    }

    private val owned = HashSet<Int>()

    private val adapter by lazy {
        ResultAdapter(
            onOpen = { openUrl(it.url) },
            onAdd = { releaseActions(it) },
            isOwned = { owned.contains(it) }
        )
    }

    private var pendingSide: Side = Side.FRONT
    private var pendingUri: Uri? = null
    private var frontUri: Uri? = null
    private var backUri: Uri? = null
    private var frontDone = false
    private var backDone = false
    private var lastFromCamera = false
    private var sessionAdded = 0
    private val checkedNotOwned = HashSet<Int>()
    private var frontLines: List<String> = emptyList()
    private var backLines: List<String> = emptyList()

    private val token: String get() = prefs.getString("token", "").orEmpty()
    private val username: String get() = prefs.getString("username", "").orEmpty()
    private val folderId: Int get() = prefs.getInt("folder_id", 1)
    private val folderName: String get() = prefs.getString("folder_name", "Uncategorized").orEmpty()
    private val chainMode: Boolean get() = prefs.getBoolean("chain", true)
    private val mode: Mode
        get() = if (prefs.getString("mode", "SLEEVE") == "DISC") Mode.DISC else Mode.SLEEVE

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingUri
            when {
                ok && uri != null -> onImage(pendingSide, uri)
                // en mode à la chaîne, reculer sur le verso = « ce disque n'en a pas besoin »
                pendingSide == Side.BACK && frontDone -> {
                    status.text = "Verso passé, recherche sur artiste + titre."
                    search()
                }
                else -> status.text = "Photo annulée."
            }
        }

    private val pickBackup =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) restoreBackup(uri)
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onImage(pendingSide, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayShowTitleEnabled(false) // le logo remplace le titre

        imgFront = findViewById(R.id.imgFront)
        imgBack = findViewById(R.id.imgBack)
        lblFront = findViewById(R.id.lblFront)
        lblBack = findViewById(R.id.lblBack)
        queryInput = findViewById(R.id.queryInput)
        catnoInput = findViewById(R.id.catnoInput)
        chips = findViewById(R.id.chips)
        vinylOnly = findViewById(R.id.vinylOnly)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        recycler = findViewById(R.id.recycler)
        emptyState = findViewById(R.id.emptyState)
        cardFront = findViewById(R.id.cardFront)
        cardBack = findViewById(R.id.cardBack)
        titleFront = findViewById(R.id.titleFront)
        titleBack = findViewById(R.id.titleBack)
        modeGroup = findViewById(R.id.modeGroup)

        modeGroup.check(if (mode == Mode.DISC) R.id.modeDisc else R.id.modeSleeve)
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = if (checkedId == R.id.modeDisc) "DISC" else "SLEEVE"
            if (prefs.getString("mode", "SLEEVE") == newMode) return@addOnButtonCheckedListener
            prefs.edit().putString("mode", newMode).apply()
            applyMode()
            reset()
        }
        applyMode()

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        owned.addAll(prefs.getStringSet("owned", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() })

        cardFront.setOnClickListener { chooseSource(Side.FRONT) }
        cardBack.setOnClickListener { chooseSource(Side.BACK) }
        imgFront.setOnClickListener { chooseSource(Side.FRONT) }
        imgBack.setOnClickListener { chooseSource(Side.BACK) }

        findViewById<View>(R.id.btnSearch).setOnClickListener { search() }
        findViewById<View>(R.id.btnScan).setOnClickListener { scanBarcode() }
        findViewById<View>(R.id.btnSideline).setOnClickListener { sideline() }
        findViewById<View>(R.id.btnCreate).setOnClickListener { draftDialog(null) }
        findViewById<View>(R.id.btnWeb).setOnClickListener {
            val c = criteria()
            if (c.isEmpty()) toast("Rien à chercher.") else openUrl(DiscogsApi.webSearchUrl(c))
        }

        catnoInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }

        when {
            token.isBlank() ->
                status.text = "Connecte ton compte Discogs (menu ⋮ > Compte Discogs) pour chercher et enrichir ta collection."
            username.isBlank() -> verifyAccount(token, silent = true)
            else -> {
                status.text = "Connecté : $username · dossier « $folderName »."
                ensureFieldIds()
                retryPending()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_chain)?.isChecked = chainMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_chain -> {
            val on = !chainMode
            prefs.edit().putBoolean("chain", on).apply()
            item.isChecked = on
            status.text = if (on)
                "Mode à la chaîne activé : le verso s'enchaîne, et un nouveau disque démarre après chaque ajout."
            else
                "Mode à la chaîne désactivé : chaque photo se déclenche à la main."
            true
        }
        R.id.action_library -> { startActivity(Intent(this, LibraryActivity::class.java)); true }
        R.id.action_backup -> { backupDialog(); true }
        R.id.action_import -> { importCollection(); true }
        R.id.action_tracks -> { completeTracks(); true }
        R.id.action_box -> { boxDialog(); true }
        R.id.action_sidelined -> { pileDialog(Store.REVIEW); true }
        R.id.action_drafts -> { pileDialog(Store.CREATE); true }
        R.id.action_journal -> { journalDialog(); true }
        R.id.action_condition -> { conditionDialog(); true }
        R.id.action_account -> { accountDialog(); true }
        R.id.action_folder -> { chooseFolder(); true }
        R.id.action_help -> { showHelp(); true }
        R.id.action_reset -> { reset(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Change les libellés et les visuels selon ce qu'on photographie. */
    private fun applyMode() {
        val disc = mode == Mode.DISC
        titleFront.text = getString(if (disc) R.string.slot_a else R.string.slot_front)
        titleBack.text = getString(if (disc) R.string.slot_b else R.string.slot_back)
        lblFront.text = getString(if (disc) R.string.slot_label_sub else R.string.slot_front_sub)
        lblBack.text = getString(if (disc) R.string.slot_label_sub else R.string.slot_back_sub)
        if (!frontDone) imgFront.setImageResource(placeholder())
        if (!backDone) imgBack.setImageResource(placeholder())
    }

    private fun placeholder(): Int =
        if (mode == Mode.DISC) R.drawable.img_vinyl_label else R.drawable.img_turntable

    // ---------- Capture recto / verso ----------

    private fun chooseSource(side: Side) {
        pendingSide = side
        val titre = when {
            mode == Mode.DISC && side == Side.FRONT -> "Face A · étiquette du disque"
            mode == Mode.DISC -> "Face B · étiquette ou dead wax"
            side == Side.FRONT -> "Recto (pochette)"
            else -> "Verso (dos ou étiquette centrale)"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titre)
            .setItems(arrayOf("Appareil photo", "Galerie")) { _, which ->
                lastFromCamera = which == 0
            if (which == 0) shootPhoto() else pickImage.launch("image/*")
            }
            .show()
    }

    private fun shootPhoto() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("Appareil photo indisponible : ${e.message}")
        }
    }

    private fun onImage(side: Side, uri: Uri) {
        if (side == Side.FRONT) frontUri = uri else backUri = uri
        val img = if (side == Side.FRONT) imgFront else imgBack
        img.setPadding(0, 0, 0, 0)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.load(uri)
        val card = if (side == Side.FRONT) cardFront else cardBack
        card.strokeColor = ContextCompat.getColor(this, R.color.gold)
        card.strokeWidth = (2 * resources.displayMetrics.density).toInt()
        runOcr(side, uri)
    }

    private fun setResultsVisible(hasResults: Boolean) {
        emptyState.visibility = if (hasResults) View.GONE else View.VISIBLE
    }

    // ---------- OCR local ----------

    private suspend fun recognize(image: InputImage): Text? = suspendCancellableCoroutine { cont ->
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(image)
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    /** Décodage réduit + redressement EXIF, pour pouvoir relire l'image sous plusieurs angles. */
    private fun decodeBitmap(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2200) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val deg = contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        when {
            bmp == null -> null
            deg == 0 -> bmp
            else -> Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(deg.toFloat()) }, true)
        }
    } catch (e: Exception) {
        null
    }

    private fun runOcr(side: Side, uri: Uri) {
        progress.visibility = View.VISIBLE
        status.text = if (mode == Mode.DISC) "Lecture de l'étiquette…" else "Lecture du texte…"

        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeBitmap(uri) }
            if (bmp == null) {
                progress.visibility = View.GONE
                status.text = "Image illisible."
                return@launch
            }

            // Un vrai code-barres sur la photo vaut mieux que sa lecture en OCR
            val scanned = detectBarcode(bmp)
            if (scanned != null && catnoInput.text.isBlank()) catnoInput.setText(scanned)

            // Sur une étiquette, le texte tourne avec le disque : on relit dans les quatre sens.
            val angles = if (mode == Mode.DISC) listOf(0, 90, 180, 270) else listOf(0)
            val texts = ArrayList<Text>()
            for (a in angles) {
                withContext(Dispatchers.Default) { InputImage.fromBitmap(bmp, a) }
                    .let { recognize(it) }
                    ?.let { texts.add(it) }
            }

            // Gravure de dead wax : si la première passe ne donne rien, on renforce le contraste
            if (texts.sumOf { it.text.length } < 4) {
                status.text = "Rien de lisible, seconde passe avec renfort de contraste…"
                val boosted = withContext(Dispatchers.Default) { ImagePrep.enhance(bmp) }
                if (boosted != null) {
                    for (a in angles) {
                        withContext(Dispatchers.Default) { InputImage.fromBitmap(boosted, a) }
                            .let { recognize(it) }
                            ?.let { texts.add(it) }
                    }
                }
            }

            progress.visibility = View.GONE
            if (texts.isEmpty()) {
                status.text = "Aucun texte détecté. Rapproche-toi, à plat et bien éclairé."
                return@launch
            }

            when {
                mode == Mode.DISC -> handleLabel(side, texts)
                side == Side.FRONT -> handleFront(texts)
                else -> handleBack(texts)
            }
            refreshChips()
            maybeAutoSearch(side)
        }
    }

    private fun mergedCandidates(texts: List<Text>, max: Int): List<String> =
        texts.flatMap { OcrQuery.candidates(it, max) }.distinctBy { it.lowercase() }.take(max)

    private fun mergedCatnos(texts: List<Text>): List<String> =
        texts.flatMap { OcrQuery.extractCatalogNumbers(it) }.distinct().take(6)

    /** Mode pochette : le recto donne artiste + titre. */
    private fun handleFront(texts: List<Text>) {
        frontLines = mergedCandidates(texts, 10)
        frontDone = true
        lblFront.text = getString(R.string.slot_front_done)
        if (queryInput.text.isBlank() && frontLines.isNotEmpty()) {
            queryInput.setText(OcrQuery.suggestQuery(frontLines))
        }
        status.text = if (frontLines.isEmpty())
            "Aucun texte lisible au recto. Photographie maintenant le verso."
        else
            "Recto lu. Photographie le verso pour le n° de catalogue."

        if (chainMode && lastFromCamera && !backDone) {
            pendingSide = Side.BACK
            imgFront.postDelayed({ shootPhoto() }, 350)
        }
    }

    /** Mode pochette : le verso donne le n° de catalogue et le code-barres. */
    private fun handleBack(texts: List<Text>) {
        backDone = true
        lblBack.text = getString(R.string.slot_back_done)

        val barcode = texts.firstNotNullOfOrNull { OcrQuery.extractBarcode(it) }
        val catnos = mergedCatnos(texts)
        backLines = (catnos + mergedCandidates(texts, 6)).distinct()

        when {
            barcode != null -> {
                if (catnoInput.text.isBlank()) catnoInput.setText(barcode)
                status.text = "Code-barres détecté : $barcode — c'est le critère le plus précis."
            }
            catnos.isNotEmpty() -> {
                if (catnoInput.text.isBlank()) catnoInput.setText(catnos.first())
                status.text = "N° probable : ${catnos.first()} (touche une étiquette pour en essayer un autre)."
            }
            else -> status.text = "Ni code-barres ni n° trouvé au verso. Recherche sur artiste + titre."
        }

        if (queryInput.text.isBlank() && backLines.isNotEmpty()) {
            queryInput.setText(OcrQuery.suggestQuery(backLines))
        }
    }

    /**
     * Mode disque : sur un white label ou une pochette générique, les deux faces portent
     * aussi bien l'artiste que le n° de catalogue. On prend donc tout, des deux côtés.
     */
    private fun handleLabel(side: Side, texts: List<Text>) {
        val lines = mergedCandidates(texts, 8)
        val catnos = mergedCatnos(texts)
        val barcode = texts.firstNotNullOfOrNull { OcrQuery.extractBarcode(it) }

        if (side == Side.FRONT) {
            frontLines = lines
            frontDone = true
            lblFront.text = getString(R.string.slot_front_done)
        } else {
            backLines = (catnos + lines).distinct()
            backDone = true
            lblBack.text = getString(R.string.slot_back_done)
        }

        if (queryInput.text.isBlank() && lines.isNotEmpty()) {
            queryInput.setText(OcrQuery.suggestQuery(lines))
        }
        if (catnoInput.text.isBlank()) {
            val found = barcode ?: catnos.firstOrNull()
            if (found != null) catnoInput.setText(found)
        }

        val face = if (side == Side.FRONT) "Face A" else "Face B"
        status.text = when {
            barcode != null -> "$face lue · identifiant $barcode"
            catnos.isNotEmpty() -> "$face lue · n° probable ${catnos.first()}"
            lines.isNotEmpty() -> "$face lue · ${lines.first()}"
            else -> "$face : rien de lisible. Lumière rasante et cadrage serré sur l'étiquette."
        }

        if (side == Side.FRONT && chainMode && lastFromCamera && !backDone) {
            pendingSide = Side.BACK
            imgFront.postDelayed({ shootPhoto() }, 350)
        }
    }

    private fun refreshChips() {
        chips.removeAllViews()
        for (c in backLines) addChip(c, toCatno = true)
        for (c in frontLines) addChip(c, toCatno = false)
    }

    private fun addChip(value: String, toCatno: Boolean) {
        val chip = Chip(this)
        chip.text = value
        chip.isCheckable = false
        chip.setOnClickListener {
            val looksLikeCatno = toCatno && value.any { it.isDigit() } && value.length <= 14
            if (looksLikeCatno) {
                catnoInput.setText(value)
            } else {
                val cur = queryInput.text.toString().trim()
                queryInput.setText(if (cur.isBlank()) value else "$cur $value")
            }
        }
        chips.addView(chip)
    }

    private fun maybeAutoSearch(side: Side) {
        if (frontDone && backDone) search()
        else if (side == Side.BACK && catnoInput.text.isNotBlank()) search()
    }

    // ---------- Code-barres ----------

    /**
     * Scanner en direct, fourni par les services Google Play : pas de permission caméra à
     * demander, l'aperçu tourne dans leur processus. Le module se télécharge au premier usage.
     */
    private fun scanBarcode() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39
            )
            .enableAutoZoom()
            .build()

        status.text = "Vise le code-barres…"
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code ->
                val value = code.rawValue.orEmpty().trim()
                if (value.isBlank()) {
                    status.text = "Code illisible, réessaie."
                    return@addOnSuccessListener
                }
                catnoInput.setText(value)
                status.text = "Code-barres scanné : $value"
                search()
            }
            .addOnCanceledListener { status.text = "Scan annulé." }
            .addOnFailureListener { e ->
                status.text = "Scanner indisponible (${e.message}). " +
                        "Photographie le dos : le code sera lu sur l'image."
            }
    }

    /** Lecture passive : chaque photo prise est aussi examinée à la recherche d'un code-barres. */
    private suspend fun detectBarcode(bmp: Bitmap): String? = suspendCancellableCoroutine { cont ->
        BarcodeScanning.getClient()
            .process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { codes ->
                val value = codes.firstNotNullOfOrNull { it.rawValue?.trim() }
                if (cont.isActive) cont.resume(value?.takeIf { it.length >= 6 })
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
    }

    // ---------- Recherche ----------

    private fun criteria(): Criteria {
        val q = queryInput.text.toString().trim()
        val raw = catnoInput.text.toString().trim()
        val digits = raw.filter { it.isDigit() }
        // EAN-8, UPC-A, EAN-13 : tout ce qui est purement numérique et assez long
        val isBarcode = raw.isNotBlank() && raw.none { it.isLetter() } && digits.length in 8..14
        return Criteria(
            q = q,
            catno = if (isBarcode) "" else raw,
            barcode = if (isBarcode) digits else "",
            vinylOnly = vinylOnly.isChecked
        )
    }

    private fun search() {
        val base = criteria()
        if (base.isEmpty()) {
            toast("Photographie au moins une face.")
            return
        }
        if (token.isBlank()) {
            status.text = "Pas de compte connecté : ouverture du site Discogs."
            openUrl(DiscogsApi.webSearchUrl(base))
            return
        }

        val attempts = ArrayList<Criteria>()
        if (base.barcode.isNotBlank()) attempts.add(Criteria(barcode = base.barcode))
        if (base.catno.isNotBlank() && base.q.isNotBlank()) attempts.add(base.copy(barcode = ""))
        if (base.catno.isNotBlank()) attempts.add(Criteria(catno = base.catno, vinylOnly = base.vinylOnly))
        // le champ identifiants de Discogs couvre aussi la matrice gravee dans le dead wax,
        // les Label Code et les mentions Other : souvent le seul repere des micro-labels
        if (base.catno.isNotBlank()) attempts.add(Criteria(barcode = base.catno))
        if (base.q.isNotBlank()) attempts.add(Criteria(q = base.q, vinylOnly = base.vinylOnly))
        if (base.q.isNotBlank() && base.vinylOnly) attempts.add(Criteria(q = base.q, vinylOnly = false))
        // dernier recours : la ligne la plus visible seule, souvent le nom du groupe
        val firstLine = frontLines.firstOrNull() ?: base.q.split(" ").firstOrNull().orEmpty()
        if (firstLine.length >= 3 && firstLine != base.q)
            attempts.add(Criteria(q = firstLine, vinylOnly = false))

        progress.visibility = View.VISIBLE
        status.text = "Recherche sur Discogs…"

        lifecycleScope.launch {
            try {
                for ((index, c) in attempts.withIndex()) {
                    val raw = withContext(Dispatchers.IO) { DiscogsApi.search(c, token) }
                    val results = rankByCatno(raw)
                    if (results.isNotEmpty()) {
                        progress.visibility = View.GONE
                        adapter.submit(results)
                        setResultsVisible(true)
                        val precision = if (index == 0) "" else " (critères élargis)"
                        status.text = "${results.size} pressages pour ${c.label()}$precision."
                        checkOwnership(results)
                        return@launch
                    }
                }
                progress.visibility = View.GONE
                adapter.submit(emptyList())
                setResultsVisible(false)
                status.text = "Rien trouvé sur Discogs. Touche une étiquette pour essayer un autre texte, " +
                        "ou ouvre la recherche sur le site : elle est plus tolérante."
            } catch (e: Exception) {
                progress.visibility = View.GONE
                if (isNetworkError(e)) {
                    val (f, b) = currentPhotos("horsligne")
                    Store.add(
                        this@MainActivity, Item(
                            kind = Store.REVIEW, title = base.q, catno = base.catno,
                            notes = "Hors ligne", frontPath = f, backPath = b
                        )
                    )
                    status.text = "Hors ligne : disque mis de côté avec ses photos (menu ⋮)."
                } else {
                    status.text = e.message ?: "Erreur réseau."
                }
            }
        }
    }

    /** Normalise un n° pour comparer « SR-04 », « SR 04 » et « sr04 ». */
    private fun normalize(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /**
     * Le pressage dont le n° de catalogue correspond exactement à ce qu'on a lu passe devant :
     * c'est ce qui distingue l'original de ses rééditions.
     */
    private fun rankByCatno(list: List<Release>): List<Release> {
        val wanted = normalize(catnoInput.text.toString().trim())
        if (wanted.length < 3) return list
        val marked = list.map { it.copy(exactMatch = normalize(it.catno) == wanted) }
        return marked.sortedByDescending { it.exactMatch }
    }

    /** Marque discrètement les pressages déjà présents dans la collection (10 premiers). */
    private fun checkOwnership(results: List<Release>) {
        if (username.isBlank() || token.isBlank()) return
        lifecycleScope.launch {
            // 5 vérifications au maximum, et jamais deux fois le même pressage
            for (r in results.take(5)) {
                if (owned.contains(r.id) || checkedNotOwned.contains(r.id)) continue
                val n = withContext(Dispatchers.IO) {
                    try { DiscogsApi.copiesInCollection(username, r.id, token) } catch (e: Exception) { 0 }
                }
                if (n > 0) {
                    owned.add(r.id)
                    saveOwned()
                    adapter.refreshItem(r.id)
                } else {
                    if (checkedNotOwned.size > 4000) checkedNotOwned.clear()
                    checkedNotOwned.add(r.id)
                }
                delay(120) // le limiteur de DiscogsApi fait le vrai travail
            }
        }
    }

    // ---------- Collection ----------

    private fun releaseActions(release: Release) {
        if (token.isBlank()) { accountDialog(); return }
        if (username.isBlank()) { verifyAccount(token, silent = false); return }

        val already = owned.contains(release.id)
        val inLibrary = Library.hasRelease(this, release.id)
        val options = arrayOf(
            "Discogs + ma bibliothèque",
            if (inLibrary) "Ma bibliothèque (2e exemplaire)" else "Ma bibliothèque seulement",
            if (already) "Discogs seulement (2e exemplaire)" else "Discogs seulement",
            "Ajouter à ma wantlist",
            "Changer de dossier Discogs",
            "Ouvrir la fiche Discogs"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(release.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> libraryDialog(release, alsoDiscogs = true)
                    1 -> libraryDialog(release, alsoDiscogs = false)
                    2 -> addToCollection(release)
                    3 -> addToWantlist(release)
                    4 -> chooseFolder()
                    5 -> openUrl(release.url)
                }
            }
            .show()
    }

    // ---------- Bibliothèque locale ----------

    private val currentBox: String get() = prefs.getString("box", "").orEmpty()

    private fun boxDialog() {
        val input = EditText(this)
        input.setText(currentBox)
        input.hint = getString(R.string.disc_box_hint)
        val pad = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)

        MaterialAlertDialogBuilder(this)
            .setTitle("Caisse courante")
            .setMessage("Elle sera proposée par défaut à chaque ajout dans ta bibliothèque.")
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                prefs.edit().putString("box", input.text.toString().trim()).apply()
                status.text = "Caisse courante : ${input.text.toString().trim().ifBlank { "aucune" }}"
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Une fiche locale garde les photos de la recherche, le lien Discogs, la caisse et les notes. */
    private fun libraryDialog(release: Release, alsoDiscogs: Boolean) {
        val box = EditText(this)
        box.setText(currentBox)
        box.hint = getString(R.string.disc_box_hint)
        val notes = EditText(this)
        notes.hint = getString(R.string.disc_notes_hint)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.LinearLayout(this)
        wrap.orientation = android.widget.LinearLayout.VERTICAL
        wrap.setPadding(pad + pad / 2, pad / 2, pad + pad / 2, 0)
        wrap.addView(box)
        wrap.addView(notes)

        MaterialAlertDialogBuilder(this)
            .setTitle("Ajouter à ma bibliothèque")
            .setView(wrap)
            .setPositiveButton("Ajouter") { _, _ ->
                val boxName = box.text.toString().trim()
                prefs.edit().putString("box", boxName).apply()
                saveToLibrary(release, boxName, notes.text.toString().trim())
                if (alsoDiscogs) addToCollection(release) else afterAdded()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun saveToLibrary(release: Release, box: String, notes: String) {
        val parts = release.artistTitle.split(" - ", limit = 2)
        val disc = Disc(
            artist = parts.getOrNull(0)?.trim().orEmpty(),
            title = parts.getOrNull(1)?.trim().orEmpty().ifBlank { release.title },
            catno = release.catno, label = release.label, year = release.year,
            country = release.country, format = release.format, genres = release.genres,
            releaseId = release.id, discogsUrl = release.url, coverUrl = release.thumb,
            box = box, notes = notes, inDiscogs = true
        )
        Library.add(this, disc)

        // photos de la recherche + pochette, copiées pour rester consultables hors ligne
        lifecycleScope.launch {
            val photos = withContext(Dispatchers.IO) {
                listOfNotNull(frontUri, backUri).mapNotNull { uri ->
                    Library.copyPhoto(this@MainActivity, uri, disc.id).ifBlank { null }
                }
            }
            val cover = withContext(Dispatchers.IO) {
                Library.downloadCover(this@MainActivity, release.thumb, disc.id)
            }
            val tracks = withContext(Dispatchers.IO) {
                try { DiscogsApi.tracklist(release.id, token) } catch (e: Exception) { emptyList() }
            }
            Library.update(this@MainActivity, disc.copy(photos = photos, coverPath = cover, tracks = tracks))
        }
    }

    /** Fin de cycle commune : compteur, remise à zéro, disque suivant. */
    private fun afterAdded() {
        val total = Library.count(this)
        sessionAdded++
        toast("Ajouté · $sessionAdded cette session")
        if (chainMode) {
            reset()
            status.text = "$total disques en bibliothèque. Suivant : photographie le recto."
            pendingSide = Side.FRONT
            lastFromCamera = true
            imgFront.postDelayed({ shootPhoto() }, 400)
        } else {
            status.text = "Ajouté à ta bibliothèque ($total disques)."
        }
    }

    private fun addToCollection(release: Release) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val instance = withContext(Dispatchers.IO) {
                    DiscogsApi.addToCollection(username, folderId, release.id, token)
                }
                applyCondition(release.id, instance)
                Store.add(
                    this@MainActivity, Item(
                        kind = Store.ADDED, title = release.title, catno = release.catno,
                        releaseId = release.id, instanceId = instance, folderId = folderId
                    )
                )
                progress.visibility = View.GONE
                owned.add(release.id)
                saveOwned()
                adapter.refreshItem(release.id)
                afterAdded()
            } catch (e: Exception) {
                progress.visibility = View.GONE
                if (isNetworkError(e)) {
                    Store.add(
                        this@MainActivity, Item(
                            kind = Store.PENDING, title = release.title, catno = release.catno,
                            releaseId = release.id, folderId = folderId
                        )
                    )
                    status.text = "Hors ligne : ajout mis en file d'attente, il partira au retour du réseau."
                } else {
                    status.text = e.message ?: "Ajout impossible."
                }
            }
        }
    }

    private fun addToWantlist(release: Release) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DiscogsApi.addToWantlist(username, release.id, token)
                }
                progress.visibility = View.GONE
                status.text = "Ajouté à ta wantlist : ${release.title}"
                toast("Ajouté à ta wantlist")
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = e.message ?: "Ajout impossible."
            }
        }
    }

    private fun saveOwned() {
        prefs.edit().putStringSet("owned", owned.map { it.toString() }.toSet()).apply()
    }

    // ---------- Compte ----------

    private fun accountDialog() {
        val input = EditText(this)
        input.hint = "Personal access token Discogs"
        input.setText(token)
        val pad = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)

        val etat = if (username.isBlank()) "Aucun compte connecté."
        else "Connecté : $username\nDossier : $folderName"

        MaterialAlertDialogBuilder(this)
            .setTitle("Compte Discogs")
            .setMessage(
                "$etat\n\nDiscogs.com > Settings > Developers > Generate token.\n" +
                        "Ce jeton authentifie ton compte : il permet la recherche et l'ajout à ta " +
                        "collection. Il reste stocké sur le téléphone."
            )
            .setView(input)
            .setPositiveButton("Connecter") { _, _ ->
                val t = input.text.toString().trim()
                prefs.edit().putString("token", t).apply()
                if (t.isNotBlank()) verifyAccount(t, silent = false)
            }
            .setNeutralButton("Générer un jeton") { _, _ ->
                openUrl("https://www.discogs.com/settings/developers")
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun verifyAccount(t: String, silent: Boolean) {
        if (!silent) progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { DiscogsApi.identity(t) }
                progress.visibility = View.GONE
                if (name.isBlank()) {
                    status.text = "Jeton refusé par Discogs."
                    return@launch
                }
                prefs.edit().putString("username", name).apply()
                status.text = "Connecté : $name · dossier « $folderName »."
                // On récupère le nom du dossier par défaut au passage
                val fs = withContext(Dispatchers.IO) {
                    try { DiscogsApi.folders(name, t) } catch (e: Exception) { emptyList() }
                }
                fs.firstOrNull { it.id == folderId }?.let {
                    prefs.edit().putString("folder_name", it.name).apply()
                    status.text = "Connecté : $name · dossier « ${it.name} »."
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = e.message ?: "Connexion impossible."
            }
        }
    }

    private fun chooseFolder() {
        if (token.isBlank() || username.isBlank()) { accountDialog(); return }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val fs = withContext(Dispatchers.IO) { DiscogsApi.folders(username, token) }
                progress.visibility = View.GONE
                if (fs.isEmpty()) { toast("Aucun dossier trouvé."); return@launch }
                val labels = fs.map { "${it.name} (${it.count})" }.toTypedArray()
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Dossier de destination")
                    .setItems(labels) { _, which ->
                        val f = fs[which]
                        prefs.edit().putInt("folder_id", f.id).putString("folder_name", f.name).apply()
                        status.text = "Dossier de destination : « ${f.name} »."
                    }
                    .show()
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = e.message ?: "Impossible de lire les dossiers."
            }
        }
    }

    // ---------- Piles locales : à revoir, à soumettre, journal ----------

    private fun currentPhotos(prefix: String): Pair<String, String> {
        val f = frontUri?.let { Store.persistPhoto(this, it, prefix + "_A") } ?: ""
        val b = backUri?.let { Store.persistPhoto(this, it, prefix + "_B") } ?: ""
        return f to b
    }

    /** Met le disque de côté avec ses photos : rien n'est perdu, on tranchera plus tard. */
    private fun sideline() {
        val q = queryInput.text.toString().trim()
        val cat = catnoInput.text.toString().trim()
        if (q.isBlank() && cat.isBlank() && frontUri == null) {
            toast("Rien à mettre de côté.")
            return
        }
        val (f, b) = currentPhotos("revoir")
        val extra = (frontLines + backLines).distinct().take(8).joinToString(" | ")
        Store.add(
            this, Item(
                kind = Store.REVIEW, title = q, catno = cat,
                notes = extra, frontPath = f, backPath = b
            )
        )
        val n = Store.count(this, Store.REVIEW)
        toast("Mis de côté ($n)")
        reset()
        if (chainMode) {
            status.text = "$n disques à revoir. Suivant : photographie le recto."
            pendingSide = Side.FRONT
            lastFromCamera = true
            imgFront.postDelayed({ shootPhoto() }, 400)
        } else {
            status.text = "Mis de côté. $n disques en attente de réidentification."
        }
    }

    /**
     * Discogs n'a pas d'API de soumission : on prépare donc la fiche en local, photos comprises,
     * et on ouvrira le formulaire du site pour la saisir.
     */
    private fun draftDialog(existing: Item?) {
        val view = layoutInflater.inflate(R.layout.dialog_draft, null)
        val artist = view.findViewById<EditText>(R.id.dArtist)
        val title = view.findViewById<EditText>(R.id.dTitle)
        val label = view.findViewById<EditText>(R.id.dLabel)
        val catno = view.findViewById<EditText>(R.id.dCatno)
        val year = view.findViewById<EditText>(R.id.dYear)
        val format = view.findViewById<EditText>(R.id.dFormat)
        val notes = view.findViewById<EditText>(R.id.dNotes)

        if (existing != null) {
            artist.setText(existing.artist); title.setText(existing.title)
            label.setText(existing.label); catno.setText(existing.catno)
            year.setText(existing.year); format.setText(existing.format)
            notes.setText(existing.notes)
        } else {
            val q = queryInput.text.toString().trim()
            val parts = q.split(" ")
            artist.setText(parts.firstOrNull().orEmpty())
            title.setText(parts.drop(1).joinToString(" "))
            catno.setText(catnoInput.text.toString().trim())
            format.setText(if (mode == Mode.DISC) "12\"" else "LP")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Fiche à soumettre" else "Modifier la fiche")
            .setView(view)
            .setPositiveButton("Enregistrer") { _, _ ->
                val (f, b) = if (existing == null) currentPhotos("fiche") else (existing.frontPath to existing.backPath)
                val item = Item(
                    id = existing?.id ?: System.currentTimeMillis(),
                    kind = Store.CREATE,
                    artist = artist.text.toString().trim(),
                    title = title.text.toString().trim(),
                    label = label.text.toString().trim(),
                    catno = catno.text.toString().trim(),
                    year = year.text.toString().trim(),
                    format = format.text.toString().trim(),
                    notes = notes.text.toString().trim(),
                    frontPath = f, backPath = b
                )
                if (existing == null) Store.add(this, item) else Store.update(this, item)
                val n = Store.count(this, Store.CREATE)
                status.text = "$n fiches à soumettre. Menu ⋮ > Fiches à soumettre pour les exporter."
                if (existing == null) reset()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pileDialog(kind: String) {
        val items = Store.byKind(this, kind)
        val titre = if (kind == Store.REVIEW) "Disques mis de côté" else "Fiches à soumettre"
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle(titre)
                .setMessage("Rien pour l'instant.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = items.map { it.label() }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("$titre (${items.size})")
            .setItems(labels) { _, which -> itemActions(items[which]) }
            .setPositiveButton("Exporter tout") { _, _ ->
                shareItems(listOf(kind), if (kind == Store.REVIEW) "a_revoir.csv" else "a_soumettre.csv")
            }
            .setNeutralButton("Vider") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Vider la pile ?")
                    .setMessage("Les ${items.size} fiches et leurs photos seront effacées du téléphone.")
                    .setPositiveButton("Vider") { _, _ ->
                        Store.clearKind(this, kind)
                        status.text = "Pile vidée."
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun itemActions(item: Item) {
        val options = if (item.kind == Store.CREATE)
            arrayOf("Modifier", "Ouvrir le formulaire Discogs", "Exporter cette fiche", "Reprendre la recherche", "Supprimer")
        else
            arrayOf("Reprendre la recherche", "Exporter cette fiche", "En faire une fiche à soumettre", "Supprimer")

        MaterialAlertDialogBuilder(this)
            .setTitle(item.label())
            .setItems(options) { _, which ->
                if (item.kind == Store.CREATE) when (which) {
                    0 -> draftDialog(item)
                    1 -> openUrl("https://www.discogs.com/release/add")
                    2 -> shareOne(item)
                    3 -> reopen(item)
                    4 -> { Store.remove(this, item.id); status.text = "Fiche supprimée." }
                } else when (which) {
                    0 -> reopen(item)
                    1 -> shareOne(item)
                    2 -> {
                        Store.remove(this, item.id)
                        Store.add(this, item.copy(kind = Store.CREATE))
                        status.text = "Déplacé dans les fiches à soumettre."
                    }
                    3 -> { Store.remove(this, item.id); status.text = "Fiche supprimée." }
                }
            }
            .show()
    }

    private fun reopen(item: Item) {
        queryInput.setText(listOf(item.artist, item.title).filter { it.isNotBlank() }.joinToString(" "))
        catnoInput.setText(item.catno)
        status.text = "Repris depuis la pile. Modifie si besoin, puis cherche."
    }

    /** Export CSV + photos, via la feuille de partage du téléphone. */
    private fun shareItems(kinds: List<String>, fileName: String) {
        val csv = Store.exportCsv(this, kinds, fileName)
        if (csv == null) {
            toast("Export impossible.")
            return
        }
        val uris = ArrayList<Uri>()
        uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", csv))
        Store.all(this).filter { it.kind in kinds }.flatMap { it.photos() }.take(40).forEach { path ->
            val f = File(path)
            if (f.exists()) uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "VinyLens — $fileName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Exporter"))
        } catch (e: Exception) {
            toast("Aucune application pour l'export.")
        }
    }

    private fun shareOne(item: Item) {
        val text = buildString {
            appendLine("Artiste : ${item.artist}")
            appendLine("Titre : ${item.title}")
            appendLine("Label : ${item.label}")
            appendLine("N° de catalogue : ${item.catno}")
            appendLine("Année : ${item.year}")
            appendLine("Format : ${item.format}")
            appendLine("Notes : ${item.notes}")
        }
        val uris = ArrayList<Uri>()
        item.photos().forEach { path ->
            val f = File(path)
            if (f.exists()) uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", f))
        }
        val intent = Intent(if (uris.isEmpty()) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (uris.isEmpty()) "text/plain" else "*/*"
            putExtra(Intent.EXTRA_TEXT, text)
            if (uris.isNotEmpty()) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Partager la fiche"))
        } catch (e: Exception) {
            toast("Aucune application pour le partage.")
        }
    }

    // ---------- Journal de session ----------

    private fun journalDialog() {
        val items = Store.byKind(this, Store.ADDED)
        val pending = Store.count(this, Store.PENDING)
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("Journal de session")
                .setMessage(if (pending > 0) "Aucun ajout confirmé. $pending en attente de réseau."
                            else "Aucun ajout pour l'instant.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = items.map { it.label() }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Ajoutés : ${items.size}" + if (pending > 0) " · $pending en attente" else "")
            .setItems(labels) { _, which -> addedActions(items[which]) }
            .setPositiveButton("Exporter") { _, _ -> shareItems(listOf(Store.ADDED), "ajouts.csv") }
            .setNeutralButton("Effacer le journal") { _, _ ->
                Store.clearKind(this, Store.ADDED)
                status.text = "Journal effacé (la collection Discogs n'est pas touchée)."
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun addedActions(item: Item) {
        MaterialAlertDialogBuilder(this)
            .setTitle(item.label())
            .setItems(arrayOf("Ouvrir la fiche Discogs", "Retirer de ma collection")) { _, which ->
                when (which) {
                    0 -> openUrl("https://www.discogs.com/release/${item.releaseId}")
                    1 -> undoAdd(item)
                }
            }
            .show()
    }

    private fun undoAdd(item: Item) {
        if (item.instanceId <= 0) {
            toast("Exemplaire inconnu, retire-le depuis le site.")
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DiscogsApi.removeFromCollection(username, item.folderId, item.releaseId, item.instanceId, token)
                }
                progress.visibility = View.GONE
                Store.remove(this@MainActivity, item.id)
                owned.remove(item.releaseId)
                saveOwned()
                adapter.refreshItem(item.releaseId)
                if (sessionAdded > 0) sessionAdded--
                status.text = "Retiré de ta collection : ${item.title}"
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = e.message ?: "Retrait impossible."
            }
        }
    }

    // ---------- État par défaut ----------

    private val grades = arrayOf(
        "Mint (M)", "Near Mint (NM or M-)", "Very Good Plus (VG+)", "Very Good (VG)",
        "Good Plus (G+)", "Good (G)", "Fair (F)", "Poor (P)", "Ne pas renseigner"
    )

    private fun conditionDialog() {
        if (username.isBlank()) { accountDialog(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("État du disque appliqué à chaque ajout")
            .setItems(grades) { _, which ->
                val media = if (which == grades.lastIndex) "" else grades[which]
                prefs.edit().putString("grade_media", media).apply()
                sleeveDialog(media)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun sleeveDialog(media: String) {
        val sleeveGrades = grades.toMutableList().apply { add(lastIndex, "Generic") }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("État de la pochette")
            .setItems(sleeveGrades) { _, which ->
                val sleeve = if (which == sleeveGrades.lastIndex) "" else sleeveGrades[which]
                prefs.edit().putString("grade_sleeve", sleeve).apply()
                ensureFieldIds()
                status.text = when {
                    media.isBlank() && sleeve.isBlank() -> "Aucun état ne sera renseigné."
                    else -> "État appliqué à chaque ajout : ${media.ifBlank { "—" }} / ${sleeve.ifBlank { "—" }}"
                }
            }
            .show()
    }

    /** Récupère une fois pour toutes les identifiants des champs « Media » et « Sleeve ». */
    private fun ensureFieldIds() {
        if (prefs.getInt("field_media", 0) > 0 || username.isBlank()) return
        lifecycleScope.launch {
            val fields = withContext(Dispatchers.IO) {
                try { DiscogsApi.collectionFields(username, token) } catch (e: Exception) { emptyList() }
            }
            val media = fields.firstOrNull { it.name.contains("Media", true) }
            val sleeve = fields.firstOrNull { it.name.contains("Sleeve", true) }
            prefs.edit()
                .putInt("field_media", media?.id ?: 0)
                .putInt("field_sleeve", sleeve?.id ?: 0)
                .apply()
        }
    }

    private suspend fun applyCondition(releaseId: Int, instanceId: Int) {
        if (instanceId <= 0) return
        val media = prefs.getString("grade_media", "").orEmpty()
        val sleeve = prefs.getString("grade_sleeve", "").orEmpty()
        val fMedia = prefs.getInt("field_media", 0)
        val fSleeve = prefs.getInt("field_sleeve", 0)
        withContext(Dispatchers.IO) {
            try {
                if (media.isNotBlank() && fMedia > 0)
                    DiscogsApi.setInstanceField(username, folderId, releaseId, instanceId, fMedia, media, token)
                if (sleeve.isNotBlank() && fSleeve > 0)
                    DiscogsApi.setInstanceField(username, folderId, releaseId, instanceId, fSleeve, sleeve, token)
            } catch (e: Exception) {
                // l'ajout est fait : un état non renseigné n'est pas bloquant
            }
        }
    }

    // ---------- File d'attente hors-ligne ----------

    private fun isNetworkError(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return e is java.io.IOException &&
                (m.contains("unable to resolve host") || m.contains("timeout") ||
                 m.contains("failed to connect") || m.contains("network") || m.contains("unreachable"))
    }

    private fun retryPending() {
        val pending = Store.byKind(this, Store.PENDING)
        if (pending.isEmpty() || username.isBlank() || token.isBlank()) return
        lifecycleScope.launch {
            var done = 0
            for (item in pending) {
                try {
                    val instance = withContext(Dispatchers.IO) {
                        DiscogsApi.addToCollection(username, item.folderId, item.releaseId, token)
                    }
                    Store.remove(this@MainActivity, item.id)
                    Store.add(this@MainActivity, item.copy(kind = Store.ADDED, instanceId = instance))
                    applyCondition(item.releaseId, instance)
                    done++
                } catch (e: Exception) {
                    break // toujours hors ligne : on réessaiera au prochain lancement
                }
            }
            if (done > 0) status.text = "$done ajout(s) en attente envoyés à Discogs."
        }
    }

    // ---------- Sauvegarde, import, morceaux ----------

    private fun backupDialog() {
        val discs = Library.count(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("Sauvegarde")
            .setMessage("$discs disques en bibliothèque. L'archive contient les fiches, les piles et toutes les photos.")
            .setItems(arrayOf("Créer une archive", "Restaurer une archive", "Exporter la bibliothèque en CSV")) { _, which ->
                when (which) {
                    0 -> exportBackup()
                    1 -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Restaurer")
                            .setMessage("Les fiches absentes seront ajoutées ; celles déjà présentes ne sont pas touchées.")
                            .setPositiveButton("Choisir l'archive") { _, _ -> pickBackup.launch("*/*") }
                            .setNegativeButton("Annuler", null)
                            .show()
                    }
                    2 -> exportLibraryCsv()
                }
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun exportBackup() {
        progress.visibility = View.VISIBLE
        status.text = "Création de l'archive…"
        lifecycleScope.launch {
            val zip = withContext(Dispatchers.IO) { Backup.export(this@MainActivity) }
            progress.visibility = View.GONE
            if (zip == null) {
                status.text = "Sauvegarde impossible."
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", zip)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, zip.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            status.text = "Archive prête (${zip.length() / 1024} Ko) : ${zip.name}"
            try {
                startActivity(Intent.createChooser(intent, "Sauvegarder vers…"))
            } catch (e: Exception) {
                toast("Aucune application pour recevoir l'archive.")
            }
        }
    }

    private fun restoreBackup(uri: Uri) {
        progress.visibility = View.VISIBLE
        status.text = "Restauration…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Backup.restore(this@MainActivity, uri) }
            progress.visibility = View.GONE
            status.text = if (r.error != null) "Échec : ${r.error}"
            else "Restauré : ${r.discs} disques, ${r.items} fiches en pile, ${r.photos} images."
        }
    }

    private fun exportLibraryCsv() {
        val discs = Library.all(this)
        if (discs.isEmpty()) { toast("Bibliothèque vide."); return }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val sb = StringBuilder("Artiste;Titre;Label;N° catalogue;Année;Pays;Format;Genres;Caisse;Notes;Discogs\n")
                    fun cell(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
                    for (d in discs.sortedBy { it.sortKey(false) }) {
                        sb.append(
                            listOf(
                                cell(d.artist), cell(d.title), cell(d.label), cell(d.catno),
                                cell(d.year), cell(d.country), cell(d.format),
                                cell(d.genres.joinToString(", ")), cell(d.box), cell(d.notes),
                                cell(d.discogsUrl)
                            ).joinToString(";")
                        )
                        sb.append('\n')
                    }
                    val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
                    val f = java.io.File(dir, "bibliotheque.csv")
                    f.writeText(sb.toString())
                    f
                } catch (e: Exception) { null }
            }
            if (file == null) { toast("Export impossible."); return@launch }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(intent, "Exporter la bibliothèque"))
            } catch (e: Exception) {
                toast("Aucune application pour l'export.")
            }
        }
    }

    /** Verse la collection Discogs existante dans la bibliothèque locale, page par page. */
    private fun importCollection() {
        if (username.isBlank() || token.isBlank()) { accountDialog(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Importer ma collection Discogs")
            .setMessage("Les pressages déjà présents en bibliothèque sont ignorés. Les pochettes " +
                    "resteront chargées depuis Discogs : prévois du réseau pour les voir.")
            .setPositiveButton("Importer") { _, _ -> runImport() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun runImport() {
        progress.visibility = View.VISIBLE
        status.text = "Import de ta collection…"
        lifecycleScope.launch {
            var page = 1
            var pages = 1
            var added = 0
            var skipped = 0
            try {
                while (page <= pages) {
                    val (discs, total) = withContext(Dispatchers.IO) {
                        DiscogsApi.collectionPage(username, token, page)
                    }
                    pages = total
                    for (d in discs) {
                        if (Library.hasRelease(this@MainActivity, d.releaseId)) { skipped++; continue }
                        Library.add(this@MainActivity, d.copy(box = currentBox))
                        added++
                    }
                    status.text = "Import : page $page/$pages · $added ajoutés"
                    page++
                }
                progress.visibility = View.GONE
                status.text = "Import terminé : $added disques ajoutés, $skipped déjà présents."
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = "Import interrompu (${e.message}). $added disques déjà enregistrés."
            }
        }
    }

    /** Complète les tracklists manquantes : une requête par disque, donc en tâche de fond. */
    private fun completeTracks() {
        if (token.isBlank()) { accountDialog(); return }
        val todo = Library.withoutTracks(this)
        if (todo.isEmpty()) { toast("Toutes les fiches ont déjà leurs morceaux."); return }

        val minutes = maxOf(1, todo.size / 50)
        MaterialAlertDialogBuilder(this)
            .setTitle("Récupérer les morceaux")
            .setMessage("${todo.size} fiches sans tracklist. Une requête par disque, soit environ " +
                    "$minutes minute(s) en respectant la limite Discogs. Tu peux continuer à " +
                    "utiliser l'app pendant ce temps, mais reste dans l'application.")
            .setPositiveButton("Lancer") { _, _ ->
                lifecycleScope.launch {
                    var done = 0
                    for (d in todo) {
                        val tracks = withContext(Dispatchers.IO) {
                            try { DiscogsApi.tracklist(d.releaseId, token) } catch (e: Exception) { emptyList() }
                        }
                        if (tracks.isNotEmpty()) {
                            Library.get(this@MainActivity, d.id)?.let {
                                Library.update(this@MainActivity, it.copy(tracks = tracks))
                            }
                            done++
                        }
                        if (done % 10 == 0) status.text = "Morceaux récupérés : $done/${todo.size}"
                    }
                    status.text = "Morceaux récupérés pour $done disques."
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- Divers ----------

    private fun reset() {
        frontDone = false; backDone = false
        frontUri = null; backUri = null
        frontLines = emptyList(); backLines = emptyList()
        val pad = (10 * resources.displayMetrics.density).toInt()
        for (img in listOf(imgFront, imgBack)) {
            img.setImageResource(placeholder())
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setPadding(pad, pad, pad, pad)
        }
        for (card in listOf(cardFront, cardBack)) {
            card.strokeColor = ContextCompat.getColor(this, R.color.line)
            card.strokeWidth = (1 * resources.displayMetrics.density).toInt()
        }
        applyMode()
        queryInput.setText("")
        catnoInput.setText("")
        chips.removeAllViews()
        adapter.submit(emptyList())
        setResultsVisible(false)
        status.text = if (username.isBlank()) getString(R.string.status_idle)
        else "Connecté : $username · dossier « $folderName »."
    }

    private fun showHelp() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Comment ca marche - v$version")
            .setMessage(
                "Deux modes, en haut de l'écran :\n\n" +
                        "• Pochette — recto (artiste, titre) puis verso (n° de catalogue, code-barres).\n" +
                        "• Étiquettes du disque — pour les white labels et pochettes génériques : " +
                        "face A puis face B. Les deux faces sont fouillées pour l'artiste ET le n°, " +
                        "et l'image est relue dans les quatre sens, car le texte tourne avec le disque.\n\n" +
                        "3. Le texte est lu hors-ligne, aucune image ne quitte le téléphone.\n" +
                        "4. La recherche part uniquement sur api.discogs.com, du critère le plus " +
                        "précis (code-barres) au plus large (artiste + titre).\n" +
                        "5. Touche un résultat pour ouvrir sa fiche, ou le bouton + pour l'ajouter " +
                        "à ta collection ou à ta wantlist.\n\n" +
                        "Sauvegarde (menu ⋮) : crée une archive ZIP avec toutes tes fiches et photos, à ranger " +
                        "sur Drive ou ailleurs. C'est le seul filet si tu perds le téléphone.\n\n" +
                        "Importer ma collection Discogs verse tes disques déjà enregistrés dans la " +
                        "bibliothèque locale. Récupérer les morceaux complète les tracklists, ce qui " +
                        "permet de chercher un titre de morceau et de retrouver la caisse.\n\n" +
                        "Dans la bibliothèque, appui long sur une fiche pour en sélectionner plusieurs " +
                        "et les déplacer d'une caisse à l'autre d'un coup.\n\n" +
                        "Ma bibliothèque (menu ⋮) est ton catalogue local : chaque disque y garde ses photos, " +
                        "son lien Discogs, sa caisse de rangement et tes notes. Recherche par texte, " +
                        "tri A→Z ou par ajout, filtres par genre et par caisse. La fiche accepte " +
                        "autant de photos que tu veux.\n\n" +
                        "Deux boutons sous le message : « Mettre de côté » garde le disque et ses photos " +
                        "pour plus tard, « Pas sur Discogs » prépare une fiche à soumettre. " +
                        "Les deux piles s'exportent en CSV avec les photos depuis le menu ⋮.\n\n" +
                        "Le journal de session liste tes ajouts et permet d'en retirer un de la " +
                        "collection. L'état par défaut (VG+, NM…) est appliqué automatiquement à " +
                        "chaque ajout.\n\n" +
                        "Une coche verte signale un pressage déjà présent dans ta collection — " +
                        "pratique pour ne pas cataloguer deux fois le même disque.\n\n" +
                        "Mode à la chaîne (menu ⋮) : après le recto, l'appareil photo repart tout seul " +
                        "sur le verso, et un nouveau disque démarre dès qu'un pressage est ajouté. " +
                        "Le bouton à droite du champ n° ouvre un scanner de code-barres en direct ; " +
                        "et de toute façon, chaque photo prise est aussi examinée pour y trouver un code.\n\n" +
                        "Reculer pendant la photo du verso lance la recherche sans lui.\n\n" +
                        "Disques sans code-barres : photographie l'étiquette centrale, ou même le " +
                        "dead wax (la zone lisse près du trou, à la lumière rasante). Le code gravé " +
                        "là est cherché comme identifiant Discogs, au même titre qu'un code-barres. " +
                        "Sinon, groupe + titre suffisent le plus souvent."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Aucun navigateur trouvé.")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
