package org.crea.reader.bookshelf

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.crea.reader.R
import org.crea.reader.data.model.Book
import org.crea.reader.data.model.DriveBook

/**
 * A book in the "Seguir leyendo" carousel, downloaded ([book]) or read online from Drive ([drive]),
 * with its progress for whoever reads now and its AI summary.
 */
data class ContinueItem(
    val title: String,
    val author: String?,
    val cover: String?,
    val percent: Int,
    val summary: String?,
    val book: Book? = null,
    val drive: DriveBook? = null,
) {
    val key: String get() = drive?.driveFileId ?: book?.id?.toString().orEmpty()
}

/**
 * Top of "Mi biblioteca": a carousel with the last books being read (swipe between them, dots
 * below) and a row with the books added most recently. Tap opens, long press shows the book's card.
 */
@Composable
fun LibraryHighlights(
    reading: List<ContinueItem>,
    recent: List<Book>,
    onOpen: (ContinueItem) -> Unit,
    onDetails: (ContinueItem) -> Unit,
    onBookDetails: (Book) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 12.dp)) {
        if (reading.isNotEmpty()) ContinueCarousel(reading, onOpen, onDetails)
        if (recent.isNotEmpty()) RecentRow(recent, onBookDetails)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCarousel(items: List<ContinueItem>, onOpen: (ContinueItem) -> Unit, onDetails: (ContinueItem) -> Unit) {
    val pager = rememberPagerState { items.size }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 10.dp,
            key = { items[it].key },
        ) { page ->
            ContinueCard(items[page], onOpen, onDetails)
        }
        if (items.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                repeat(items.size) { index ->
                    val selected = index == pager.currentPage
                    Box(
                        Modifier.size(if (selected) 8.dp else 6.dp).clip(CircleShape)
                            .background(colorResource(if (selected) R.color.crea_primary else R.color.crea_on_surface_variant).copy(alpha = if (selected) 1f else 0.4f))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(item: ContinueItem, onOpen: (ContinueItem) -> Unit, onDetails: (ContinueItem) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(colorResource(R.color.hero_start), colorResource(R.color.hero_end))))
            .border(1.dp, Color.White.copy(alpha = 0.5f), shape)
            .combinedClickable(onLongClick = { onDetails(item) }, onClick = { onOpen(item) })
            .padding(14.dp),
    ) {
        BookCover(item.cover, 84.dp, 126.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(stringResource(R.string.library_continue_label), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
            Text(
                item.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
            item.author?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.summary?.let {
                Text(
                    it, fontSize = 12.sp, lineHeight = 16.sp, color = Color.White.copy(alpha = 0.85f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                LinearProgressIndicator(
                    progress = { item.percent / 100f },
                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
                Text(
                    stringResource(R.string.book_progress_label, item.percent), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(books: List<Book>, onDetails: (Book) -> Unit) {
    Column {
        Text(
            stringResource(R.string.library_recent_label), fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = colorResource(R.color.crea_on_surface), modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { it.id ?: 0L }) { book ->
                Column(
                    Modifier.width(92.dp).clip(RoundedCornerShape(10.dp))
                        .combinedClickable(onLongClick = { onDetails(book) }, onClick = { onDetails(book) })
                ) {
                    BookCover(book.cover, 92.dp, 136.dp)
                    Text(
                        book.title.orEmpty(), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = colorResource(R.color.crea_on_surface), modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCover(path: String?, width: Dp, height: Dp) {
    AndroidView(
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { view ->
            if (path != null) CoverImages.request(path).placeholder(R.drawable.cover).into(view) else view.setImageResource(R.drawable.cover)
        },
        modifier = Modifier.width(width).height(height).shadow(6.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)),
    )
}
