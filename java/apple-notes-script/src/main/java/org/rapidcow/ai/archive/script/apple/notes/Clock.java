package org.rapidcow.ai.archive.script.apple.notes;

/**
 * SortID from {@code com.apple.drawing.2}.
 */
record Clock(int clock, int replicaIndex, int subClock)
	implements Comparable<Clock>
{
	@Override public int compareTo(Clock o)
	{
		int cmp;
		if (( cmp = Integer.compare(clock, o.clock) ) != 0) {
			return cmp;
		}
		if (( cmp = Integer.compare(replicaIndex, o.replicaIndex) ) != 0) {
			return cmp;
		}
		if (( cmp = Integer.compare(subClock, o.subClock) ) != 0) {
			return cmp;
		}
		return 0;
	}
}
