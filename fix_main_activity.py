with open("OsmAnd-Cellular-Surround/app/src/main/java/com/example/osmandcellularsurround/MainActivity.kt", "r") as f:
    content = f.read()

import re
# We added a duplicate onCreate by accident with sed
content = re.sub(r'override fun onCreate\(savedInstanceState: Bundle\?\) \{\s+super\.onCreate\(savedInstanceState\)\s+OpenCellidApi\.init\(this\)\s+OpenCellidDownloader\.init\(this\)', '', content)
content = content.replace("override fun onCreate(savedInstanceState: Bundle?) {", "override fun onCreate(savedInstanceState: Bundle?) {\n        OpenCellidApi.init(this)\n        OpenCellidDownloader.init(this)", 1)

with open("OsmAnd-Cellular-Surround/app/src/main/java/com/example/osmandcellularsurround/MainActivity.kt", "w") as f:
    f.write(content)
