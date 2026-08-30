package com.pvmperformance;

// The ranged boost a player means to be holding. Only the ranging potion, and its divine form, are worth listing:
// nothing else outside a raid reaches higher, and the raid doses raise the standard on their own while they run.
public enum RangedBoost
{
	NONE("None")
		{
			@Override
			int boost(int base)
			{
				return 0;
			}
		},

	RANGING("Ranging potion")
		{
			@Override
			int boost(int base)
			{
				return base / 10 + 4;
			}
		};

	private final String displayName;

	RangedBoost(String displayName)
	{
		this.displayName = displayName;
	}

	abstract int boost(int base);

	@Override
	public String toString()
	{
		return displayName;
	}
}
