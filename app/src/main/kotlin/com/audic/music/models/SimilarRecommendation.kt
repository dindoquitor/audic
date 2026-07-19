

package com.audic.music.models

import com.music.innertube.models.YTItem
import com.audic.music.db.entities.LocalItem

data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)
