/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/Debug.java#1 $
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
 * Equivalent to struct lua_Debug.  This implementation is incomplete
 * because it is not intended to form part of the public API.  It has
 * only been implemented to the extent necessary for internal use.
 */
final class Debug
{
  // private, no public accessors defined.
  private int ici;

  // public accessors may be defined for these.
  private int event;
  private String what;
  private String source;
  private int currentline;
  private int linedefined;
  private int lastlinedefined;
  private String shortsrc;

  /**
   * @param ici  index of CallInfo record in L.civ
   */
  Debug(int ici)
  {
    this.ici = ici;
  }

  /**
   * Get ici, index of the {@link CallInfo} record.
   */
  int ici()
  {
    return ici;
  }

  /**
   * Setter for event.
   */
  void setEvent(int event)
  {
    this.event = event;
  }

  /**
   * Sets the what field.
   */
  void setWhat(String what)
  {
    this.what = what;
  }

  /**
   * Sets the source, and the shortsrc.
   */
  void setSource(String source)
  {
    this.source = source;
    this.shortsrc = Lua.oChunkid(source);
  }

  /**
   * Gets the current line.  May become public.
   */
  int currentline()
  {
    return currentline;
  }

  /**
   * Set currentline.
   */
  void setCurrentline(int currentline)
  {
    this.currentline = currentline;
  }

  /**
   * Get linedefined.
   */
  int linedefined()
  {
    return linedefined;
  }

  /**
   * Set linedefined.
   */
  void setLinedefined(int linedefined)
  {
    this.linedefined = linedefined;
  }

  /**
   * Set lastlinedefined.
   */
  void setLastlinedefined(int lastlinedefined)
  {
    this.lastlinedefined = lastlinedefined;
  }

  /**
   * Gets the "printable" version of source, for error messages.
   * May become public.
   */
  String shortsrc()
  {
    return shortsrc;
  }
}
