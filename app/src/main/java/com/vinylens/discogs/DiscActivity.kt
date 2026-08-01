package com.vinylens.discogs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import java.io.File

class DiscActivity : AppCompatActivity() {

    private var disc: Disc? = null
    private var pendingPhoto: Uri? = null

    private lateinit var cover: ImageView
    private lateinit var artistView: TextView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var genreChips: ChipGroup
    private lateinit var boxValue: TextView
    private lateinit var notesValue: TextView
    private lateinit var photoList: RecyclerView
    private lateinit var noPhotos: TextView

    private val photoAdapter = PhotoAdapter(
        onClick = { path -> viewPhoto(path) },
        onLongClick = { path -> confirmRemovePhoto(path) }
    )

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingPhoto
            if (ok && uri != null) attachPhoto(uri)
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) attachPhoto(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disc)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        cover = findViewById(R.id.cover)
        artistView = findViewById(R.id.artist)
        titleView = findViewById(R.id.title)
        metaView = findViewById(R.id.meta)
        genreChips = findViewById(R.id.genreChips)
        boxValue = findViewById(R.id.boxValue)
        notesValue = findViewById(R.id.notesValue)
        photoList = findViewById(R.id.photoList)
        noPhotos = findViewById(R.id.noPhotos)

        photoList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        photoList.adapter = photoAdapter

        findViewById<View>(R.id.boxRow).setOnClickListener { editBox() }
        findViewById<View>(R.id.notesRow).setOnClickListener { editNotes() }
        findViewById<MaterialButton>(R.id.btnAddPhoto).setOnClickListener { addPhoto() }
        findViewById<MaterialButton>(R.id.btnDiscogs).setOnClickListener { openDiscogs() }
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { confirmDelete() }

        val id = intent.getLongExtra("id", 0L)
        disc = Library.get(this, id)
        if (disc == null) {
            toast("Fiche introuvable.")
            finish()
            return
        }
        bind()
    }

    private fun bind() {
        val d = disc ?: return
        artistView.text = d.artist.ifBlank { d.heading() }
        titleView.text = d.title
        titleView.visibility = if (d.title.isBlank()) View.GONE else View.VISIBLE
        metaView.text = d.subheading()

        val source: Any? = when {
            d.coverPath.isNotBlank() && File(d.coverPath).exists() -> File(d.coverPath)
            d.photos.any { File(it).exists() } -> File(d.photos.first { File(it).exists() })
            d.coverUrl.isNotBlank() -> d.coverUrl
            else -> null
        }
        if (source != null) {
            cover.load(source) {
                crossfade(true)
                placeholder(R.drawable.placeholder_cover)
                error(R.drawable.placeholder_cover)
            }
        } else {
            cover.setImageResource(R.drawable.placeholder_cover)
        }

        genreChips.removeAllViews()
        for (g in d.genres) {
            val chip = Chip(this)
            chip.text = g
            chip.isCheckable = false
            genreChips.addView(chip)
        }

        boxValue.text = d.box.ifBlank { getString(R.string.disc_box_empty) }
        notesValue.text = d.notes.ifBlank { getString(R.string.disc_notes_empty) }

        val existing = d.photos.filter { File(it).exists() }
        photoAdapter.submit(existing)
        photoList.visibility = if (existing.isEmpty()) View.GONE else View.VISIBLE
        noPhotos.visibility = if (existing.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun save(updated: Disc) {
        disc = updated
        Library.update(this, updated)
        bind()
    }

    // ---------- Champs personnels ----------

    private fun editBox() {
        val d = disc ?: return
        val input = EditText(this)
        input.setText(d.box)
        input.hint = getString(R.string.disc_box_hint)
        val pad = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)

        val known = Library.boxes(this).filter { it != d.box }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disc_box)
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                save(d.copy(box = input.text.toString().trim()))
            }
            .setNegativeButton("Annuler", null)

        if (known.isNotEmpty()) {
            builder.setNeutralButton("Caisses existantes") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.disc_box)
                    .setItems(known.toTypedArray()) { _, which -> save(d.copy(box = known[which])) }
                    .show()
            }
        }
        builder.show()
    }

    private fun editNotes() {
        val d = disc ?: return
        val input = EditText(this)
        input.setText(d.notes)
        input.hint = getString(R.string.disc_notes_hint)
        input.minLines = 3
        val pad = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disc_notes)
            .setView(input)
            .setPositiveButton("Enregistrer") { _, _ ->
                save(d.copy(notes = input.text.toString().trim()))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- Photos ----------

    private fun addPhoto() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disc_add_photo)
            .setItems(arrayOf("Appareil photo", "Galerie")) { _, which ->
                if (which == 0) shootPhoto() else pickImage.launch("image/*")
            }
            .show()
    }

    private fun shootPhoto() {
        try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val f = File(dir, "disc_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            pendingPhoto = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("Appareil photo indisponible.")
        }
    }

    private fun attachPhoto(uri: Uri) {
        val d = disc ?: return
        val path = Library.copyPhoto(this, uri, d.id)
        if (path.isBlank()) {
            toast("Photo non enregistrée.")
            return
        }
        save(d.copy(photos = d.photos + path))
    }

    private fun viewPhoto(path: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            toast("Aucune visionneuse d'images.")
        }
    }

    private fun confirmRemovePhoto(path: String) {
        val d = disc ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer cette photo ?")
            .setPositiveButton("Supprimer") { _, _ ->
                try { File(path).delete() } catch (e: Exception) { }
                save(d.copy(photos = d.photos.filterNot { it == path }))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- Actions ----------

    private fun openDiscogs() {
        val d = disc ?: return
        val url = d.discogsUrl.ifBlank {
            if (d.releaseId > 0) "https://www.discogs.com/release/${d.releaseId}" else ""
        }
        if (url.isBlank()) {
            toast("Aucun lien Discogs pour cette fiche.")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Aucun navigateur trouvé.")
        }
    }

    private fun share() {
        val d = disc ?: return
        val text = buildString {
            appendLine(d.heading())
            if (d.subheading().isNotBlank()) appendLine(d.subheading())
            if (d.genres.isNotEmpty()) appendLine("Genres : ${d.genres.joinToString(", ")}")
            if (d.box.isNotBlank()) appendLine("Rangé dans : ${d.box}")
            if (d.notes.isNotBlank()) appendLine("Notes : ${d.notes}")
            if (d.discogsUrl.isNotBlank()) appendLine(d.discogsUrl)
        }
        val uris = ArrayList<Uri>()
        (listOf(d.coverPath) + d.photos).filter { it.isNotBlank() && File(it).exists() }.forEach {
            uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", File(it)))
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

    private fun confirmDelete() {
        val d = disc ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer la fiche ?")
            .setMessage("La fiche et ses photos seront effacées du téléphone. Ta collection Discogs n'est pas touchée.")
            .setPositiveButton("Supprimer") { _, _ ->
                Library.delete(this, d)
                finish()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

class PhotoAdapter(
    private val onClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.VH>() {

    private val items = ArrayList<String>()

    fun submit(list: List<String>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val photo: ShapeableImageView = v.findViewById(R.id.photo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val path = items[position]
        holder.photo.load(File(path)) {
            crossfade(true)
            placeholder(R.drawable.placeholder_cover)
            error(R.drawable.placeholder_cover)
        }
        holder.photo.setOnClickListener { onClick(path) }
        holder.photo.setOnLongClickListener { onLongClick(path); true }
    }

    override fun getItemCount(): Int = items.size
}
