/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.shared.model

/** The grid cells a tile takes. */
data class TileCells(val columns: Int, val rows: Int)

/** Where a tile sits in the grid. */
data class TilePlacement(val column: Int, val row: Int, val columns: Int, val rows: Int)

/** Tracks the taken cells of a grid and finds the first free spot for a tile. */
private class CellGrid(private val columns: Int) {
    private val taken = ArrayList<BooleanArray>()

    fun place(tile: TileCells, maxRows: Int): TilePlacement? {
        var row = 0
        while (row + tile.rows <= maxRows) {
            for (column in 0..columns - tile.columns) {
                if (isFree(column, row, tile)) {
                    take(column, row, tile)
                    return TilePlacement(column, row, tile.columns, tile.rows)
                }
            }
            row++
        }
        return null
    }

    private fun isFree(column: Int, row: Int, tile: TileCells): Boolean {
        for (y in row until row + tile.rows) {
            val cells = taken.getOrNull(y) ?: return true
            for (x in column until column + tile.columns) {
                if (cells[x]) return false
            }
        }
        return true
    }

    private fun take(column: Int, row: Int, tile: TileCells) {
        while (taken.size < row + tile.rows) taken.add(BooleanArray(columns))
        for (y in row until row + tile.rows) {
            for (x in column until column + tile.columns) taken[y][x] = true
        }
    }
}

/** Places each tile in the first free spot, so smaller tiles fill the gaps next to bigger ones. */
fun packTiles(tiles: List<TileCells>, columns: Int): List<TilePlacement> {
    val grid = CellGrid(columns)
    return tiles.map { checkNotNull(grid.place(it, Int.MAX_VALUE)) }
}

/** Splits [tiles] into pages of [rows] rows. The result holds the tile indexes of each page. */
fun paginateTiles(tiles: List<TileCells>, columns: Int, rows: Int): List<List<Int>> {
    if (tiles.isEmpty()) return emptyList()
    val pages = mutableListOf(mutableListOf<Int>())
    var grid = CellGrid(columns)
    tiles.forEachIndexed { index, tile ->
        val fitted = tile.copy(rows = tile.rows.coerceAtMost(rows))
        if (grid.place(fitted, rows) == null) {
            grid = CellGrid(columns)
            grid.place(fitted, rows)
            pages.add(mutableListOf())
        }
        pages.last().add(index)
    }
    return pages
}

/** The cells of the tile [spec], on a grid of [columns] columns. */
fun QsTweaks.cellsOf(
    spec: String,
    theme: QsStyle,
    isLarge: Boolean,
    largeSpan: Int,
    columns: Int,
): TileCells {
    val shape = shapeFrom ?: theme
    if (spec == QsMediaTileSize.TILE_SPEC) return mediaCells(shape, columns)
    val long = TileCells(largeSpan.coerceAtMost(columns), 1)
    val own = tiles[spec]
    val auto =
        when {
            sizing == QsTileSizing.LONG -> long
            sizing == QsTileSizing.ICON -> TileCells(1, 1)
            !shape.uniformTiles && isLarge -> long
            else -> TileCells(1, 1)
        }
    return TileCells(
        (own?.columns ?: auto.columns).coerceIn(1, columns),
        (own?.rows ?: auto.rows).coerceAtLeast(1),
    )
}

private fun QsTweaks.mediaCells(shape: QsStyle, columns: Int): TileCells {
    val short = (tileHeight ?: if (shape == QsStyle.COMPACT) 48 else 64) < 56
    val block = if (columns >= 5) 3 else 2.coerceAtMost(columns)
    val rows = if (short) 3 else 2
    return when (mediaSize ?: QsMediaTileSize.SQUARE) {
        QsMediaTileSize.SMALL -> TileCells(1, 1)
        QsMediaTileSize.WIDE -> TileCells(columns, 1)
        QsMediaTileSize.SQUARE -> TileCells(block, rows)
        QsMediaTileSize.TALL -> TileCells(block, rows + 1)
        QsMediaTileSize.FULL -> TileCells(columns, rows)
    }
}
