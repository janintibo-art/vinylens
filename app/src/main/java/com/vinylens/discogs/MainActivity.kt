package com.vinylens.discogs

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private enum class Side { FRONT, BACK }

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
    private var frontLines: List<String> = emptyList()
    private var backLines: List<String> = emptyList()

    private val token: String get() = prefs.getString("token", "").orEmpty()
    private val username: String get() = prefs.getString("username", "").orEmpty()
    private val folderId: Int get() = prefs.getInt("folder_id", 1)
    private val folderName: String get() = prefs.getString("folder_name", "Uncategorized").orEmpty()

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingUri
            if (ok && uri != null) onImage(pendingSide, uri) else status.text = "Photo annulée."
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

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        owned.addAll(prefs.getStringSet("owned", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() })

        imgFront.setOnClickListener { chooseSource(Side.FRONT) }
        imgBack.setOnClickListener { chooseSource(Side.BACK) }

        findViewById<MaterialButton>(R.id.btnSearch).setOnClickListener { search() }
        findViewById<MaterialButton>(R.id.btnWeb).setOnClickListener {
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_account -> { accountDialog(); true }
        R.id.action_folder -> { chooseFolder(); true }
        R.id.action_help -> { showHelp(); true }
        R.id.action_reset -> { reset(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------- Capture recto / verso ----------

    private fun chooseSource(side: Side) {
        pendingSide = side
        val titre = if (side == Side.FRONT) "Recto (pochette)" else "Verso (dos ou étiquette centrale)"
        AlertDialog.Builder(this)
            .setTitle(titre)
            .setItems(arrayOf("Appareil photo", "Galerie")) { _, which ->
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
        if (side == Side.FRONT) imgFront.load(uri) else imgBack.load(uri)
        runOcr(side, uri)
    }

    // ---------- OCR local ----------

    private fun runOcr(side: Side, uri: Uri) {
        progress.visibility = View.VISIBLE
        status.text = if (side == Side.FRONT) "Lecture du recto…" else "Lecture du verso…"

        lifecycleScope.launch {
            val image = try {
                withContext(Dispatchers.IO) { InputImage.fromFilePath(this@MainActivity, uri) }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = "Image illisible : ${e.message}"
                return@launch
            }

            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image)
                .addOnSuccessListener { text ->
                    progress.visibility = View.GONE
                    if (side == Side.FRONT) handleFront(text) else handleBack(text)
                    refreshChips()
                    maybeAutoSearch(side)
                }
                .addOnFailureListener { e ->
                    progress.visibility = View.GONE
                    status.text = "Échec de la lecture : ${e.message}"
                }
        }
    }

    private fun handleFront(text: com.google.mlkit.vision.text.Text) {
        frontLines = OcrQuery.candidates(text)
        frontDone = true
        lblFront.text = "Recto ✓"
        if (queryInput.text.isBlank() && frontLines.isNotEmpty()) {
            queryInput.setText(OcrQuery.suggestQuery(frontLines))
        }
        status.text = if (frontLines.isEmpty())
            "Aucun texte lisible au recto. Photographie maintenant le verso."
        else
            "Recto lu. Photographie le verso pour le n° de catalogue."
    }

    private fun handleBack(text: com.google.mlkit.vision.text.Text) {
        backDone = true
        lblBack.text = "Verso ✓"

        val barcode = OcrQuery.extractBarcode(text)
        val catnos = OcrQuery.extractCatalogNumbers(text)
        backLines = (catnos + OcrQuery.candidates(text, 6)).distinct()

        when {
            barcode != null -> {
                catnoInput.setText(barcode)
                status.text = "Code-barres détecté : $barcode — c'est le critère le plus précis."
            }
            catnos.isNotEmpty() -> {
                catnoInput.setText(catnos.first())
                status.text = "N° de catalogue probable : ${catnos.first()} (touche une étiquette pour en essayer un autre)."
            }
            else -> status.text = "Ni code-barres ni n° de catalogue trouvé au verso. Recherche sur artiste + titre."
        }

        if (queryInput.text.isBlank() && backLines.isNotEmpty()) {
            queryInput.setText(OcrQuery.suggestQuery(backLines))
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
        if (base.q.isNotBlank()) attempts.add(Criteria(q = base.q, vinylOnly = base.vinylOnly))
        if (base.q.isNotBlank() && base.vinylOnly) attempts.add(Criteria(q = base.q, vinylOnly = false))

        progress.visibility = View.VISIBLE
        status.text = "Recherche sur Discogs…"

        lifecycleScope.launch {
            try {
                for ((index, c) in attempts.withIndex()) {
                    val results = withContext(Dispatchers.IO) { DiscogsApi.search(c, token) }
                    if (results.isNotEmpty()) {
                        progress.visibility = View.GONE
                        adapter.submit(results)
                        val precision = if (index == 0) "" else " (critères élargis)"
                        status.text = "${results.size} pressages pour ${c.label()}$precision."
                        checkOwnership(results)
                        return@launch
                    }
                }
                progress.visibility = View.GONE
                adapter.submit(emptyList())
                status.text = "Aucun résultat. Vérifie le n° de catalogue ou garde seulement l'artiste."
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
            for (r in results.take(10)) {
                if (owned.contains(r.id)) continue
                val n = withContext(Dispatchers.IO) {
                    try { DiscogsApi.copiesInCollection(username, r.id, token) } catch (e: Exception) { 0 }
                }
                if (n > 0) {
                    owned.add(r.id)
                    saveOwned()
                    adapter.refreshItem(r.id)
                }
                delay(250) // on reste loin de la limite Discogs (60 requêtes/min)
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

        AlertDialog.Builder(this)
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
                status.text = "Ajouté à « $folderName » : ${release.title}"
                toast("Ajouté à ta collection Discogs")
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

        AlertDialog.Builder(this)
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
                AlertDialog.Builder(this@MainActivity)
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
        imgFront.setImageResource(R.drawable.ic_slot_empty)
        imgBack.setImageResource(R.drawable.ic_slot_empty)
        lblFront.text = getString(R.string.slot_front)
        lblBack.text = getString(R.string.slot_back)
        queryInput.setText("")
        catnoInput.setText("")
        chips.removeAllViews()
        adapter.submit(emptyList())
        status.text = if (username.isBlank()) getString(R.string.status_idle)
        else "Connecté : $username · dossier « $folderName »."
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Comment ça marche")
            .setMessage(
                "1. Recto : la pochette, bien à plat → artiste et titre.\n" +
                        "2. Verso : le dos ou l'étiquette centrale → n° de catalogue et code-barres.\n" +
                        "3. Le texte est lu hors-ligne, aucune image ne quitte le téléphone.\n" +
                        "4. La recherche part uniquement sur api.discogs.com, du critère le plus " +
                        "précis (code-barres) au plus large (artiste + titre).\n" +
                        "5. Touche un résultat pour ouvrir sa fiche, ou le bouton + pour l'ajouter " +
                        "à ta collection ou à ta wantlist.\n\n" +
                        "Une coche verte signale un pressage déjà présent dans ta collection — " +
                        "pratique pour ne pas cataloguer deux fois le même disque."
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
