/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/DumpedInput.java#1 $
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

import java.io.InputStream;

/**
 * Converts a string obtained using string.dump into an
 * {@link java.io.InputStream} so that it can be passed to {@link
 * Lua#load(java.io.InputStream, java.lang.String)}.
 */
final class DumpedInput extends InputStream
{
  private String s;
  private int i;        // = 0
  int mark = -1;

  DumpedInput(String s)
  {
    this.s = s;
  }

  public int available()
  {
    return s.length() - i;
  }

  public void close()
  {
    s = null;
    i = -1;
  }

  public void mark(int readlimit)
  {
    mark = i;
  }

  public boolean markSupported()
  {
    return true;
  }

  public int read()
  {
    if (i >= s.length())
    {
      return -1;
    }
    char c = s.charAt(i);
    ++i;
    return c&0xff;
  }

  public void reset()
  {
    i = mark;
  }
}
