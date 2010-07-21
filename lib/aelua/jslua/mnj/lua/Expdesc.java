/*  $Header: //info.ravenbrook.com/project/jili/version/1.1/code/mnj/lua/Expdesc.java#1 $
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

/** Equivalent to struct expdesc. */
final class Expdesc
{

  static final int VVOID = 0;           // no value
  static final int VNIL = 1;
  static final int VTRUE = 2;
  static final int VFALSE = 3;
  static final int VK = 4;              // info = index into 'k'
  static final int VKNUM = 5;           // nval = numerical value
  static final int VLOCAL = 6;          // info = local register
  static final int VUPVAL = 7;          // info = index into 'upvalues'
  static final int VGLOBAL = 8;         // info = index of table;
                                        // aux = index of global name in 'k'
  static final int VINDEXED = 9;        // info = table register
                                        // aux = index register (or 'k')
  static final int VJMP = 10;           // info = instruction pc
  static final int VRELOCABLE = 11;     // info = instruction pc
  static final int VNONRELOC = 12;      // info = result register
  static final int VCALL = 13;          // info = instruction pc
  static final int VVARARG = 14;        // info = instruction pc

  int k;        // one of V* enums above
  int info;
  int aux;
  double nval;
  int t;
  int f;

  Expdesc()
  {
  }

  Expdesc(int k, int i)
  {
    init(k, i);
  }

  /** Equivalent to init_exp from lparser.c */
  void init(int kind, int i)
  {
    this.t = FuncState.NO_JUMP;
    this.f = FuncState.NO_JUMP;
    this.k = kind;
    this.info = i;
  }

  void init(Expdesc e)
  {
    // Must initialise all members of this.
    this.k = e.k;
    this.info = e.info;
    this.aux = e.aux;
    this.nval = e.nval;
    this.t = e.t;
    this.f = e.f;
  }

  int kind()
  {
    return k;
  }

  void setKind(int kind)
  {
    this.k = kind;
  }

  int info()
  {
    return info;
  }

  void setInfo(int i)
  {
    this.info = i;
  }

  int aux()
  {
    return aux;
  }

  double nval()
  {
    return nval;
  }

  void setNval(double d)
  {
    this.nval = d;
  }

  /** Equivalent to hasmultret from lparser.c */
  boolean hasmultret()
  {
    return k == VCALL || k == VVARARG;
  }

  /** Equivalent to hasjumps from lcode.c. */
  boolean hasjumps()
  {
    return t != f;
  }

  void nonreloc(int i)
  {
    k = VNONRELOC;
    info = i;
  }

  void reloc(int i)
  {
    k = VRELOCABLE;
    info = i;
  }

  void upval(int i)
  {
    k = VUPVAL;
    info = i;
  }
}
