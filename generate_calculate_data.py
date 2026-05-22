import re

with open("OsmAnd-Cellular-Surround/app/src/main/java/com/example/osmandcellularsurround/MainActivity.kt", "r") as f:
    content = f.read()

# We need to make sure that the tvUserProfileLink and tvDocumentationLink are being properly overwritten in calculateCurrentMapData, which they are.
# But what about the early return where cellInfo is empty? If cellInfo is empty, gnss still runs, but currently it returns before gnss runs if cellInfo string is empty.
