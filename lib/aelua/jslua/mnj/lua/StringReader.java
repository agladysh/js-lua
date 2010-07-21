/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/StringReader.java#1 $
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

/** Ersatz replacement for {@link java.io.StringReader} from JSE. */
final class StringReader extends java.io.Reader
{
  private String s;
  /** Index of the current read position.  -1 if closed. */
  private int current;  // = 0
  /**
   * Index of the current mark (set with {@link #mark}).
   */
  private int mark;     // = 0;

  StringReader(String s)
  {
    this.s = s;
  }

  public void close()
  {
    current = -1;
  }

  public void mark(int limit)
  {
    mark = current;
  }

  public boolean markSupported()
  {
    return true;
  }

  public int read() throws IOException
  {
    if (current < 0)
    {
      throw new IOException();
    }
    if (current >= s.length())
    {
      return -1;
    }
    return s.charAt(current++);
  }

  public int read(char[] cbuf, int off, int len) throws IOException
  {
    if (current < 0 || len < 0)
    {
      throw new IOException();
    }
    if (current >= s.length())
    {
      return 0;
    }
    if (current + len > s.length())
    {
      len = s.length() - current;
    }
    for (int i=0; i<len; ++i)
    {
      cbuf[off+i] = s.charAt(current+i);
    }
    current += len;
    return len;
  }

  public void reset()
  {
    current = mark;
  }
}
