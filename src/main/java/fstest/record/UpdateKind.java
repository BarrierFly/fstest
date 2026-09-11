package fstest.record;

/**
 * Subtype of one block-update dispatch, mirroring the subset of Carpet TIS
 * Addition's {@code BlockUpdateType} that the collector observes on 1.21.11:
 *
 * <ul>
 *   <li>{@link #BLOCK_UPDATE} - {@code Level#updateNeighborsAt(pos, block)}:
 *       notify all six neighbours;</li>
 *   <li>{@link #BLOCK_UPDATE_EXCEPT} -
 *       {@code updateNeighborsAtExceptFromFacing(pos, block, direction)}: same,
 *       but the {@code direction} side is skipped;</li>
 *   <li>{@link #COMPARATOR_UPDATE} -
 *       {@code Level#updateNeighbourForOutputSignal(pos, block)}: the
 *       "analog output changed" notifier, which only reaches comparators
 *       (directly adjacent or one block behind a conductor);</li>
 *   <li>{@link #SINGLE_BLOCK_UPDATE} -
 *       {@code neighborChanged(pos/state, ..., block)}: a direct update of one
 *       specific position.</li>
 * </ul>
 *
 * TIS's {@code STATE_UPDATE} / {@code SINGLE_STATE_UPDATE} (shape updates) are
 * not collected in v1 - shape updates are not micro-timing events in the
 * drift model this tester diffs.
 */
public enum UpdateKind
{
	BLOCK_UPDATE("BU"),
	BLOCK_UPDATE_EXCEPT("BU_EXCEPT"),
	COMPARATOR_UPDATE("COMPARATOR"),
	SINGLE_BLOCK_UPDATE("SINGLE");

	private final String label;

	UpdateKind(String label)
	{
		this.label = label;
	}

	/** Compact label used in event signatures and log output. */
	public String label()
	{
		return this.label;
	}
}
