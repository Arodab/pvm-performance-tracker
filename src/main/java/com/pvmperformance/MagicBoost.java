package com.pvmperformance;

// The magic boost a player means to be holding. Figures from the wiki's
// temporary skill boost tables; the hearts and the brew differ enough that
// holding one to another's standard would misjudge it by several levels.
public enum MagicBoost
{
	NONE("None")
		{
			@Override
			int boost(int base)
			{
				return 0;
			}
		},

	SATURATED_HEART("Saturated heart")
		{
			@Override
			int boost(int base)
			{
				return base / 10 + 4;
			}
		},

	IMBUED_HEART("Imbued heart")
		{
			@Override
			int boost(int base)
			{
				return base / 10 + 1;
			}
		},

	FORGOTTEN_BREW("Forgotten brew")
		{
			@Override
			int boost(int base)
			{
				return base * 8 / 100 + 3;
			}
		};

	private final String displayName;

	MagicBoost(String displayName)
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
