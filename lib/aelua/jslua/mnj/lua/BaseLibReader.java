/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/BaseLibReader.java#1 $
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

import java.io.IOException;

/**
 * Extends {@link java.io.Reader} to create a Reader from a Lua
 * function.  So that the <code>load</code> function from Lua's base
 * library can be implemented.
 */
final class BaseLibReader extends java.io.Reader
{
  private String s = "";
  private int i;        // = 0;
  private int mark = -1;
  private Lua L;
  private Object f;

  BaseLibReader(Lua L, Object f)
  {
    this.L = L;
    this.f = f;
  }

  public void close()
  {
    f = null;
  }

  public void mark(int l) throws IOException
  {
    if (l > 1)
    {
      throw new IOException("Readahead must be <= 1");
    }
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
      L.push(f);
      L.call(0, 1);
      if (L.isNil(L.value(-1)))
      {
        return -1;
      }
      else if(L.isString(L.value(-1)))
      {
        s = L.toString(L.value(-1));
        if (s.length() == 0)
        {
          return -1;
        }
        if (mark == i)
        {
          mark = 0;
        }
        else
        {
          mark = -1;
        }
        i = 0;
      }
      else
      {
        L.error("reader function must return a string");
      }
    }
    return s.charAt(i++);
  }

  public int read(char[] cbuf, int off, int len)
  {
    int j = 0;  // loop index required after loop
    for (j=0; j<len; ++j)
    {
      int c = read();
      if (c == -1)
      {
        if (j == 0)
        {
          return -1;
        }
        else
        {
          return j;
        }
      }
      cbuf[off+j] = (char)c;
    }
    return j;
  }

  public void reset() throws IOException
  {
    if (mark < 0)
    {
      throw new IOException("reset() not supported now");
    }
    i = mark;
  }
}
