/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/LuaInternal.java#1 $
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
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Class used to implement internal callbacks.  Currently there is only
 * one callback used, one that parses or loads a Lua chunk into binary
 * form.
 */
final class LuaInternal extends LuaJavaCallback
{
  private InputStream stream;
  private Reader reader;
  private String chunkname;

  LuaInternal(InputStream in, String chunkname)
  {
    this.stream = in;
    this.chunkname = chunkname;
  }

  LuaInternal(Reader in, String chunkname)
  {
    this.reader = in;
    this.chunkname = chunkname;
  }

  public int luaFunction(Lua L)
  {
    try
    {
      Proto p = null;

      // In either the stream or the reader case there is a way of
      // converting the input to the other type.
      if (stream != null)
      {
        stream.mark(1);
        int c = stream.read();
        stream.reset();
        
        // Convert to Reader if looks like source code instead of
        // binary.
        if (c == Loader.HEADER[0])
        {
          Loader l = new Loader(stream, chunkname);
          p = l.undump();
        }
        else
        {
          reader = new InputStreamReader(stream, "UTF-8");
          p = Syntax.parser(L, reader, chunkname);
        }
      }
      else
      {
        // Convert to Stream if looks like binary (dumped via
        // string.dump) instead of source code.
        if (reader.markSupported())
        {
          reader.mark(1);
          int c = reader.read();
          reader.reset();

          if (c == Loader.HEADER[0])
          {
            stream = new FromReader(reader);
            Loader l = new Loader(stream, chunkname);
            p = l.undump();
          }
          else
          {
            p = Syntax.parser(L, reader, chunkname);
          }
        }
        else
        {
          p = Syntax.parser(L, reader, chunkname);
        }
      }

      L.push(new LuaFunction(p,
          new UpVal[0],
          L.getGlobals()));
      return 1;
    }
    catch (IOException e)
    {
      L.push("cannot read " + chunkname + ": " + e.toString());
      L.dThrow(Lua.ERRFILE);
      return 0;
    }
  }
}
