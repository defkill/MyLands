import re

HEM_SRC = r"(?:[NSEWnsew]|[СсЮюВвЗз]|пн\.?\s*ш?\.?|пд\.?\s*ш?\.?|сх\.?\s*д?\.?|зх\.?\s*д?\.?|с\.?\s*ш\.?|ю\.?\s*ш\.?|в\.?\s*д\.?|з\.?\s*д\.?)"

# Variant: requires degree symbol ° OR hemisphere, or apostrophe
# Notice that if it has no °, no ', and no hemisphere, "48 27.887" could be DM, but "48.4647" has no space
# In "48.4647", "4" and "8.4647" had no space between them when NUM_SRC matched!
# But in dmSingle:
# (-?\d{1,3})\s*°?\s*(\d{1,2}(?:[.,]\d+))
# If \s* is empty and ° is optional, then "48.4647" matched: d="4", m="8.4647"!
# That was why! Because \s* allows 0 spaces, and °? is 0 chars!
