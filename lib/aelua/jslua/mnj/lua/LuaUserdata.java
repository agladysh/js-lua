/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/LuaUserdata.java#1 $
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
 * Models an arbitrary Java reference as a Lua value.
 * This class provides a facility that is equivalent to the userdata
 * facility provided by the PUC-Rio implementation.  It has two primary
 * uses: the first is when you wish to store an arbitrary Java reference
 * in a Lua table; the second is when you wish to create a new Lua type
 * by defining an opaque object with metamethods.  The former is
 * possible because a <code>LuaUserdata</code> can be stored in tables,
 * and passed to functions, just like any other Lua value.  The latter
 * is possible because each <code>LuaUserdata</code> supports a
 * metatable.
 */
public final class LuaUserdata
{
  private Object userdata;
  private LuaTable metatable;
  private LuaTable env;
  /**
   * Wraps an arbitrary Java reference.  To retrieve the reference that
   * was wrapped, use {@link Lua#toUserdata}.
   * @param  o The Java reference to wrap.
   */
  public LuaUserdata(Object o)
  {
    userdata = o;
  }

  /**
   * Getter for userdata.
   * @return the userdata that was passed to the constructor of this
   * instance.
   */
  Object getUserdata()
  {
    return userdata;
  }

  /**
   * Getter for metatable.
   * @return the metatable.
   */
  LuaTable getMetatable()
  {
    return metatable;
  }
  /**
   * Setter for metatable.
   * @param metatable The metatable.
   */
  void setMetatable(LuaTable metatable)
  {
    this.metatable = metatable;
  }

  /**
   * Getter for environment.
   * @return The environment.
   */
  LuaTable getEnv()
  {
    return env;
  }
  /**
   * Setter for environment.
   * @param env  The environment.
   */
  void setEnv(LuaTable env)
  {
    this.env = env;
  }
}
