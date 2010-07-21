/**
 * Main.java: JS-Lua implementation
 */

package ru.aagg.jslua;

import com.google.gwt.core.client.EntryPoint;
import mnj.lua.*;

/**
 * JSLua Main.
 */

public class Main implements EntryPoint
{
  public void onModuleLoad()
  {
    Lua L = new Lua();

    BaseLib.open(L);
    PackageLib.open(L);
    StringLib.open(L);
    TableLib.open(L);
    MathLib.open(L);
    OSLib.open(L);

    L.loadString(
        "    test='poop on you'"+
        "    for i=1,10 do"+
        "      test=test..' and you'"+
        "    end"+
        "    ",
        "test"
      );
    L.call(0, 0);
  }
}
