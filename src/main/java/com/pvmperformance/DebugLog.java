package com.pvmperformance;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;

/**
 * What the tracker saw, in two forms.
 *
 * <p>A rolling window of the last few thousand lines is kept ALWAYS, and the
 * side panel can write it out. That is the one that matters: every bug in this
 * plugin so far was reported as "I just saw something odd", and a log that has
 * to be switched on beforehand asks the player to predict which kill will go
 * wrong.
 *
 * <p>The config toggle is the second form. It streams every line to a file for
 * as long as it is on, for a session spent deliberately hunting something, and
 * it survives a crash where the window in memory does not.
 *
 * <p>Nothing here touches the disk on the client thread: a line is formatted
 * where it happens, queued, and written by the scheduler a couple of seconds
 * later.
 */
@Slf4j
@Singleton
class DebugLog
{
	private static final String DIR = "pvm-performance-tracker";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final long FLUSH_SECONDS = 2;
	/**
	 * A ceiling in case the writer stalls. Roughly twenty minutes of the busiest
	 * tick this logs, so reaching it means something is wrong with the writing
	 * rather than with the logging.
	 */
	private static final int MAX_PENDING = 50000;
	/**
	 * How much of the recent past is held in memory, ready to be written out
	 * when something looks wrong.
	 *
	 * <p>Counted in LINES rather than minutes on purpose. A busy tick writes
	 * sixteen lines and a quiet one writes none, so a window measured in time
	 * bounds nothing; measured in lines it bounds itself. Real logs run at
	 * around 0.7 lines a tick and 75 bytes a line, so this is a good twenty
	 * minutes of fighting and about 400KB at its very fullest.
	 */
	private static final int RECENT_LINES = 5000;

	private final Client client;
	private final ScheduledExecutorService executor;

	private final Queue<String> pending = new ConcurrentLinkedQueue<>();
	/**
	 * The rolling window, kept whether or not anything is being written to disk.
	 *
	 * <p>Gating this on the toggle was considered and rejected: it would only
	 * ever hold what happened after the toggle went on, which is the predict-
	 * the-bug problem the button exists to solve. What the toggle controls is
	 * the WRITING, not the watching. Guarded by its own lock rather than made
	 * concurrent, since it is touched once a tick and drained once a session.
	 */
	private final Deque<String> recent = new ArrayDeque<>(RECENT_LINES);
	private final AtomicInteger queued = new AtomicInteger();
	private final AtomicInteger dropped = new AtomicInteger();

	// Read on every logged line, so both are volatile rather than synchronized: the switch itself is rare.
	private volatile boolean on;
	private volatile Path file;
	private ScheduledFuture<?> flusher;

	@Inject
	DebugLog(Client client, ScheduledExecutorService executor)
	{
		this.client = client;
		this.executor = executor;
	}

	boolean isOn()
	{
		return on;
	}

	/** The file being written, or null while logging is off. */
	Path getFile()
	{
		return file;
	}

	synchronized void start()
	{
		if (on)
		{
			return;
		}
		file = new File(new File(RuneLite.RUNELITE_DIR, DIR),
			"pvm-debug-" + LocalDateTime.now().format(STAMP) + ".log").toPath();
		pending.clear();
		queued.set(0);
		dropped.set(0);
		on = true;
		flusher = executor.scheduleWithFixedDelay(this::flush, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
		log("debug logging to file on, plugin %s", version());
		log("send this file with a description of what you did and what you expected to see");
	}

	synchronized void stop()
	{
		if (!on)
		{
			return;
		}
		final int lost = dropped.get();
		if (lost > 0)
		{
			log("%d lines were dropped: the queue filled up", lost);
		}
		log("debug logging off");
		on = false;
		if (flusher != null)
		{
			flusher.cancel(false);
			flusher = null;
		}
		// The last few lines are still queued, so one more write after the switch goes off.
		executor.execute(this::flush);
	}

	/**
	 * One line. Always formatted, because the window is always watching - about
	 * one {@link String#format} per tick at the rate real logs run at, which is
	 * the price of being able to ask what just happened after it has happened.
	 */
	void log(String format, Object... args)
	{
		final String line;
		try
		{
			line = args.length == 0 ? format : String.format(format, args);
		}
		catch (Exception e)
		{
			// A bad format string must never take down the thing being logged.
			keep("[?] BAD LOG FORMAT: " + format);
			return;
		}
		keep("[" + tick() + "] " + line);
	}

	/** Into the window always, and onto the disk queue only while that is on. */
	private void keep(String line)
	{
		synchronized (recent)
		{
			if (recent.size() == RECENT_LINES)
			{
				recent.removeFirst();
			}
			recent.addLast(line);
		}
		if (!on)
		{
			return;
		}
		if (queued.get() >= MAX_PENDING)
		{
			dropped.incrementAndGet();
			return;
		}
		pending.add(line);
		queued.incrementAndGet();
	}

	/**
	 * Writes the window to its own file, newest session state first, and returns
	 * where it went - or null if there was nothing to write or it could not be
	 * written.
	 *
	 * <p>The header is the caller's: what was worn, which boost and prayer the
	 * player said they meant to bring, and how the plugin is configured. Nearly
	 * every figure questioned so far turned on one of those, and a list of
	 * numbers with no setup attached cannot answer for itself.
	 */
	Path saveRecent(List<String> header)
	{
		final List<String> lines;
		synchronized (recent)
		{
			if (recent.isEmpty())
			{
				return null;
			}
			lines = new ArrayList<>(recent);
		}
		final Path target = new File(new File(RuneLite.RUNELITE_DIR, DIR),
			"pvm-debug-recent-" + LocalDateTime.now().format(STAMP) + ".log").toPath();
		final StringBuilder buffer = new StringBuilder();
		buffer.append("=== PvM Performance, the last ").append(lines.size())
			.append(" lines before this was saved. Plugin ").append(version()).append(" ===")
			.append(System.lineSeparator());
		if (header != null)
		{
			for (String line : header)
			{
				buffer.append("  ").append(line).append(System.lineSeparator());
			}
		}
		buffer.append("=== log ===").append(System.lineSeparator());
		for (String line : lines)
		{
			buffer.append(line).append(System.lineSeparator());
		}
		try
		{
			Files.createDirectories(target.getParent());
			Files.write(target, buffer.toString().getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			return target;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("PvM Performance: could not write the recent debug log", e);
			return null;
		}
	}

	private int tick()
	{
		try
		{
			return client.getTickCount();
		}
		catch (Exception e)
		{
			return -1;
		}
	}

	private String version()
	{
		final Package pkg = getClass().getPackage();
		final String implementation = pkg == null ? null : pkg.getImplementationVersion();
		return implementation == null ? "dev" : implementation;
	}

	private void flush()
	{
		final Path target = file;
		if (target == null || pending.isEmpty())
		{
			return;
		}
		final StringBuilder buffer = new StringBuilder();
		String line;
		while ((line = pending.poll()) != null)
		{
			queued.decrementAndGet();
			buffer.append(line).append(System.lineSeparator());
		}
		if (buffer.length() == 0)
		{
			return;
		}
		try
		{
			Files.createDirectories(target.getParent());
			Files.write(target, buffer.toString().getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException | RuntimeException e)
		{
			// Switched off rather than retried: a log that cannot be written is not worth failing a fight over, and the
			// warning says where to look. Anything unchecked is caught for the same reason and one more - a scheduled task
			// that throws is cancelled silently, so letting one escape would stop the writing without stopping the queueing.
			on = false;
			log.warn("PvM Performance: debug log could not be written, logging off", e);
		}
	}
}
