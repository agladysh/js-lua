/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/LuaJavaCallback.java#1 $
 * Copyright (c) 2006 Nokia Corporation and/or its subsidiary(-ies).
 * All rights reserved.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject
 * to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
 * ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package mnj.lua;

/**
 * Common superclass for all Lua Java Functions.  A Lua function that
 * is implemented in Java is called a Lua Java Function.  Each such
 * function corresponds to an indirect instance of this class.  If you
 * wish to implement your own Lua Java Function then you'll need to
 * subclass this class and have one instance for each function that you
 * need.  It is recommended that you extend the class with at least one
 * member so that you can distinguish the different instances.  Whilst
 * it is possible to implement each different Lua Java Function by
 * having a new subclass for each one, this is not recommended as it
 * will increase the size of the resulting <code>.jar</code> file by a
 * large amount.
 */
public abstract class LuaJavaCallback
{
  abstract public int luaFunction(Lua L);
}
