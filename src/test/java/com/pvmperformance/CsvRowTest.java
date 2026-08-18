package com.pvmperformance;

import static org.junit.Assert.assertEquals;
import java.util.Collections;
import org.junit.Test;

/**
 * Every row must have as many columns as the header names, at every level.
 *
 * <p>This is here because it has already gone wrong once: a column was added to
 * the rows and not to the header, and the file loaded into a spreadsheet with
 * every value one place to the left from that column on. Nothing about it was
 * visible in a green build, and nothing about it looks wrong in the source.
 */
public class CsvRowTest
{
	private static final int COLUMNS = PvmPerformancePlugin.CSV_HEADER.trim().split(",", -1).length;

	private static Fight fight()
	{
		final Fight f = new Fight("Some \"quoted\" npc", NpcIds.GOBLIN, 1, 100, 0L, RaidType.TOMBS_OF_AMASCUT, 2);
		f.recordAttackMade(true, true, 10, 12);
		f.recordDamageDealt(7, 600L);
		f.recordExpected(20, 0.75, 6.5);
		f.recordTickLost(false);
		f.end(true, 1200L);
		return f;
	}

	private static int columns(String row)
	{
		// Quoted fields hold no commas here, so a plain split is enough.
		return row.trim().split(",", -1).length;
	}

	@Test
	public void theHeaderNamesEveryColumnOnce()
	{
		assertEquals(28, COLUMNS);
	}

	@Test
	public void aPhaseRowMatchesTheHeader()
	{
		assertEquals(COLUMNS, columns("phase,x,1,\"room\"," + PvmPerformancePlugin.csvRow(fight())));
	}

	@Test
	public void aRoomRowMatchesTheHeader()
	{
		final Encounter room = new Encounter("Kephri", RaidType.TOMBS_OF_AMASCUT, 0L);
		room.add(fight());
		assertEquals(COLUMNS, columns(PvmPerformancePlugin.csvRoomRow("Tombs of Amascut", 2, room)));
	}

	@Test
	public void aRaidRowMatchesTheHeader()
	{
		final Encounter room = new Encounter("Kephri", RaidType.TOMBS_OF_AMASCUT, 0L);
		room.add(fight());
		assertEquals(COLUMNS,
			columns(PvmPerformancePlugin.csvRaidRow("Tombs of Amascut", 2, Collections.singletonList(room))));
	}

	@Test
	public void aRoomWithNothingToReportStillFillsItsColumns()
	{
		// An empty room divides by zero in several places if it is not careful.
		final Encounter empty = new Encounter("Vanguards", null, 0L);
		assertEquals(COLUMNS, columns(PvmPerformancePlugin.csvRoomRow(null, 0, empty)));
	}

	@Test
	public void quotesInAnNpcNameDoNotSplitTheRow()
	{
		assertEquals(COLUMNS, columns("phase,,,\"\"," + PvmPerformancePlugin.csvRow(fight())));
	}
}
