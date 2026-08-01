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
            else -> status.text = "Connecté : $username · dossier « $folderName »."
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

            // Sur une étiquette, le texte tourne avec le disque : on relit dans les quatre sens.
            val angles = if (mode == Mode.DISC) listOf(0, 90, 180, 270) else listOf(0)
            val texts = ArrayList<Text>()
            for (a in angles) {
                withContext(Dispatchers.Default) { InputImage.fromBitmap(bmp, a) }
                    .let { recognize(it) }
                    ?.let { texts.add(it) }
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
                catnoInput.setText(barcode)
                status.text = "Code-barres détecté : $barcode — c'est le critère le plus précis."
            }
            catnos.isNotEmpty() -> {
                catnoInput.setText(catnos.first())
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

    // ---------- Recherche ----------

    private fun criteria(): Criteria {
        val q = queryInput.text.toString().trim()
        val raw = catnoInput.text.toString().trim()
        val digits = raw.filter { it.isDigit() }
        val isBarcode = raw.isNotBlank() && raw.none { it.isLetter() } &&
                (digits.length == 12 || digits.length == 13)
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
                    val results = withContext(Dispatchers.IO) { DiscogsApi.search(c, token) }
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
                status.text = e.message ?: "Erreur réseau."
            }
        }
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
        val options = arrayOf(
            if (already) "Ajouter un 2e exemplaire à « $folderName »" else "Ajouter à ma collection (« $folderName »)",
            "Ajouter à ma wantlist",
            "Changer de dossier",
            "Ouvrir la fiche Discogs"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(release.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addToCollection(release)
                    1 -> addToWantlist(release)
                    2 -> chooseFolder()
                    3 -> openUrl(release.url)
                }
            }
            .show()
    }

    private fun addToCollection(release: Release) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DiscogsApi.addToCollection(username, folderId, release.id, token)
                }
                progress.visibility = View.GONE
                owned.add(release.id)
                saveOwned()
                adapter.refreshItem(release.id)
                sessionAdded++
                toast("Ajouté · $sessionAdded cette session")
                if (chainMode) {
                    reset()
                    status.text = "$sessionAdded ajoutés. Disque suivant : photographie le recto."
                    pendingSide = Side.FRONT
                    lastFromCamera = true
                    imgFront.postDelayed({ shootPhoto() }, 400)
                } else {
                    status.text = "Ajouté à « $folderName » ($sessionAdded cette session) : ${release.title}"
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = e.message ?: "Ajout impossible."
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

    // ---------- Divers ----------

    private fun reset() {
        frontDone = false; backDone = false
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
                        "Une coche verte signale un pressage déjà présent dans ta collection — " +
                        "pratique pour ne pas cataloguer deux fois le même disque.\n\n" +
                        "Mode à la chaîne (menu ⋮) : après le recto, l'appareil photo repart tout seul " +
                        "sur le verso, et un nouveau disque démarre dès qu'un pressage est ajouté. " +
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
