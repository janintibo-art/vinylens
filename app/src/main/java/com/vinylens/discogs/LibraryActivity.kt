package com.vinylens.discogs

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.io.File

class LibraryActivity : AppCompatActivity() {

    private enum class Sort { ARTIST, TITLE, RECENT }

    private lateinit var searchInput: EditText
    private lateinit var sortChips: ChipGroup
    private lateinit var filterChips: ChipGroup
    private lateinit var countLine: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View

    private var sort = Sort.ARTIST
    private var filter: String? = null      // "genre:Techno" ou "box:Caisse 1"
    private var discs: List<Disc> = emptyList()
    private var shown: List<Disc> = emptyList()
    private val selection = LinkedHashSet<Long>()

    private lateinit var bulkBar: View
    private lateinit var bulkCount: TextView

    private val adapter = DiscAdapter(
        onClick = { disc ->
            if (selection.isEmpty()) {
                startActivity(Intent(this, DiscActivity::class.java).putExtra("id", disc.id))
            } else {
                toggle(disc)
            }
        },
        onLongClick = { disc -> toggle(disc) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        searchInput = findViewById(R.id.searchInput)
        sortChips = findViewById(R.id.sortChips)
        filterChips = findViewById(R.id.filterChips)
        countLine = findViewById(R.id.countLine)
        recycler = findViewById(R.id.recycler)
        emptyState = findViewById(R.id.emptyState)

        bulkBar = findViewById(R.id.bulkBar)
        bulkCount = findViewById(R.id.bulkCount)
        findViewById<View>(R.id.bulkBox).setOnClickListener { bulkChangeBox() }
        findViewById<View>(R.id.bulkDelete).setOnClickListener { bulkDelete() }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        buildSortChips()
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    override fun onResume() {
        super.onResume()
        discs = Library.all(this)
        buildFilterChips()
        render()
    }

    private fun buildSortChips() {
        sortChips.removeAllViews()
        val entries = listOf(
            getString(R.string.sort_artist) to Sort.ARTIST,
            getString(R.string.sort_title) to Sort.TITLE,
            getString(R.string.sort_recent) to Sort.RECENT
        )
        for ((label, value) in entries) {
            val chip = Chip(this)
            chip.text = label
            chip.isCheckable = true
            chip.isChecked = value == sort
            chip.setOnClickListener {
                sort = value
                render()
            }
            sortChips.addView(chip)
        }
    }

    private fun buildFilterChips() {
        filterChips.removeAllViews()

        val all = Chip(this)
        all.text = getString(R.string.filter_all)
        all.isCheckable = true
        all.isChecked = filter == null
        all.setOnClickListener { filter = null; render() }
        filterChips.addView(all)

        for (box in Library.boxes(this)) {
            val key = "box:$box"
            val chip = Chip(this)
            chip.text = box
            chip.isCheckable = true
            chip.isChecked = filter == key
            chip.setOnClickListener { filter = key; render() }
            filterChips.addView(chip)
        }
        for (genre in Library.genres(this).take(14)) {
            val key = "genre:$genre"
            val chip = Chip(this)
            chip.text = genre
            chip.isCheckable = true
            chip.isChecked = filter == key
            chip.setOnClickListener { filter = key; render() }
            filterChips.addView(chip)
        }
    }

    override fun onBackPressed() {
        if (selection.isNotEmpty()) {
            selection.clear()
            adapter.notifyDataSetChanged()
            updateBulkBar()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun toggle(disc: Disc) {
        if (!selection.add(disc.id)) selection.remove(disc.id)
        adapter.notifyDataSetChanged()
        updateBulkBar()
    }

    private fun updateBulkBar() {
        bulkBar.visibility = if (selection.isEmpty()) View.GONE else View.VISIBLE
        bulkCount.text = getString(R.string.bulk_selected, selection.size)
    }

    private fun selected(): List<Disc> = discs.filter { it.id in selection }

    /** Déplacer cinquante disques d'une caisse à l'autre sans ouvrir cinquante fiches. */
    private fun bulkChangeBox() {
        val input = EditText(this)
        input.hint = getString(R.string.disc_box_hint)
        input.setText(selected().firstOrNull()?.box.orEmpty())
        val pad = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(pad, pad, pad, pad)

        val known = Library.boxes(this)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.bulk_selected, selection.size))
            .setView(input)
            .setPositiveButton("Déplacer") { _, _ -> applyBox(input.text.toString().trim()) }
            .setNegativeButton("Annuler", null)
        if (known.isNotEmpty()) {
            builder.setNeutralButton("Caisses existantes") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.disc_box)
                    .setItems(known.toTypedArray()) { _, which -> applyBox(known[which]) }
                    .show()
            }
        }
        builder.show()
    }

    private fun applyBox(box: String) {
        selected().forEach { Library.update(this, it.copy(box = box)) }
        val n = selection.size
        selection.clear()
        discs = Library.all(this)
        buildFilterChips()
        render()
        Toast.makeText(this, "$n disques rangés dans « $box »", Toast.LENGTH_SHORT).show()
    }

    private fun bulkDelete() {
        val n = selection.size
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer $n fiches ?")
            .setMessage("Les fiches et leurs photos seront effacées du téléphone. Ta collection Discogs n'est pas touchée.")
            .setPositiveButton("Supprimer") { _, _ ->
                selected().forEach { Library.delete(this, it) }
                selection.clear()
                discs = Library.all(this)
                buildFilterChips()
                render()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun render() {
        val query = searchInput.text.toString()
        var list = discs.filter { it.matches(query) }

        filter?.let { f ->
            val value = f.substringAfter(':')
            list = when {
                f.startsWith("box:") -> list.filter { it.box == value }
                f.startsWith("genre:") -> list.filter { d -> d.genres.any { it == value } }
                else -> list
            }
        }

        list = when (sort) {
            Sort.ARTIST -> list.sortedBy { it.sortKey(byTitle = false) }
            Sort.TITLE -> list.sortedBy { it.sortKey(byTitle = true) }
            Sort.RECENT -> list.sortedByDescending { it.addedAt }
        }

        shown = list
        adapter.query = query
        adapter.selection = selection
        adapter.submit(list)
        updateBulkBar()
        emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        countLine.text = when {
            discs.isEmpty() -> ""
            list.size == discs.size -> resources.getQuantityString(R.plurals.disc_count, discs.size, discs.size)
            else -> getString(R.string.count_filtered, list.size, discs.size)
        }
    }
}

class DiscAdapter(
    private val onClick: (Disc) -> Unit,
    private val onLongClick: (Disc) -> Unit
) : RecyclerView.Adapter<DiscAdapter.VH>() {

    private val items = ArrayList<Disc>()
    var query: String = ""
    var selection: Set<Long> = emptySet()

    fun submit(list: List<Disc>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val heading: TextView = v.findViewById(R.id.heading)
        val subheading: TextView = v.findViewById(R.id.subheading)
        val boxTag: TextView = v.findViewById(R.id.boxTag)
        val genreTag: TextView = v.findViewById(R.id.genreTag)
        val photoFlag: ImageView = v.findViewById(R.id.photoFlag)
        val trackHit: TextView = v.findViewById(R.id.trackHit)
        val card: com.google.android.material.card.MaterialCardView =
            v as com.google.android.material.card.MaterialCardView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_disc, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.heading.text = d.heading()
        holder.subheading.text = d.subheading()

        val source: Any? = when {
            d.coverPath.isNotBlank() && File(d.coverPath).exists() -> File(d.coverPath)
            d.photos.any { File(it).exists() } -> File(d.photos.first { File(it).exists() })
            d.coverUrl.isNotBlank() -> d.coverUrl
            else -> null
        }
        if (source != null) {
            holder.cover.load(source) {
                crossfade(true)
                placeholder(R.drawable.placeholder_cover)
                error(R.drawable.placeholder_cover)
            }
        } else {
            holder.cover.setImageResource(R.drawable.placeholder_cover)
        }

        holder.boxTag.visibility = if (d.box.isBlank()) View.GONE else View.VISIBLE
        holder.boxTag.text = d.box
        holder.genreTag.text = d.genres.take(2).joinToString(" · ")
        holder.photoFlag.visibility = if (d.photos.isEmpty()) View.GONE else View.VISIBLE

        val hit = d.matchingTrack(query)
        holder.trackHit.visibility = if (hit == null) View.GONE else View.VISIBLE
        holder.trackHit.text = if (hit == null) "" else "♪ $hit"

        // ContextCompat.getColorStateList est nullable : on passe par la surcharge entière
        val picked = d.id in selection
        val density = holder.itemView.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        holder.card.strokeWidth = if (picked) 3 * density else density
        holder.card.setStrokeColor(
            androidx.core.content.ContextCompat.getColor(
                holder.itemView.context,
                if (picked) R.color.gold else R.color.line
            )
        )

        holder.itemView.setOnClickListener { onClick(d) }
        holder.itemView.setOnLongClickListener { onLongClick(d); true }
    }

    override fun getItemCount(): Int = items.size
}
