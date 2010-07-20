js-lua: Run Lua from JavaScript
===============================

About
-----

JS-Lua is a JavaScript library which allows you to run Lua
code in browser (on the client-side). JS-Lua also provides
means for embedded Lua code to call JavaScript.

JS-Lua is still in its embryonic phase of development.
An appropriate description would be added later.

See the copyright information in the file named `COPYRIGHT`.

Technologies
------------

-- Jill, Lua implementation in JAVA (MIT license, http://code.google.com/p/jillcode/)

-- Google Web Toolkit, used as Java to JavaScript compiler (Apache License 2.0, http://code.google.com/webtoolkit/).

-- aelua, used as GWT port of Jill (MIT license, http://code.google.com/p/aelua/).

Note to the library users: everything is built-in, you do not need any of tools listed here to use with JS-Lua.

API
---

### JS

JSLua.callLua(functionName, args, ...) --> array of return values

### Lua

JSLua.callJS(functionName, args, ...) --> return value
