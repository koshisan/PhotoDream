package de.koshi.photodream.util

import de.koshi.photodream.model.Asset
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Smart shuffle algorithm: 50% recent photos, 50% older photos
 */
object SmartShuffle {
    
    private const val RECENT_DAYS = 7L
    private const val RECENT_RATIO = 0.5
    
    /**
     * Shuffle assets with smart algorithm
     * - 50% from recent photos (last 7 days)
     * - 50% from older photos
     * - Both groups shuffled randomly
     */
    fun shuffle(
        assets: List<Asset>,
        recentDays: Long = RECENT_DAYS,
        recentRatio: Double = RECENT_RATIO
    ): List<Asset> {
        if (assets.isEmpty()) return emptyList()
        
        val cutoff = Instant.now().minus(recentDays, ChronoUnit.DAYS)
        
        // Separate into recent and older
        val (recent, older) = assets.partition { asset ->
            val createdAt = asset.getCreationInstant()
            createdAt != null && createdAt.isAfter(cutoff)
        }
        
        // Shuffle both groups
        val recentShuffled = recent.shuffled()
        val olderShuffled = older.shuffled()
        
        // Calculate how many from each group
        val totalCount = assets.size
        val recentCount = (totalCount * recentRatio).toInt().coerceAtMost(recentShuffled.size)
        val olderCount = (totalCount - recentCount).coerceAtMost(olderShuffled.size)
        
        // If one group is too small, fill from the other
        val finalRecent = if (recentShuffled.size < recentCount) {
            recentShuffled
        } else {
            recentShuffled.take(recentCount)
        }
        
        val finalOlder = if (olderShuffled.size < olderCount) {
            olderShuffled
        } else {
            olderShuffled.take(olderCount)
        }
        
        // Combine and shuffle again for final mix
        return (finalRecent + finalOlder).shuffled()
    }
    
    /**
     * Simple random shuffle (fallback)
     */
    fun simpleShuffle(assets: List<Asset>): List<Asset> {
        return assets.shuffled()
    }
}
