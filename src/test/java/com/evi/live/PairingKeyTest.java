package com.evi.live;

public final class PairingKeyTest {
  public static void main(String[] args) {
    String key="abcdef0123456789".repeat(4);
    if(!key.equals(PairingKey.normalize("﻿ "+key.toUpperCase()+"\r\n")))throw new AssertionError("Normalize text-file BOM, whitespace and hex case");
    for(String invalid:new String[]{"", " ", "Scanner key: "+key, key.substring(1), key+"0", "g".repeat(64)}) {
      try {PairingKey.normalize(invalid);throw new AssertionError("Malformed key accepted");}
      catch(IllegalArgumentException expected){}
    }
    System.out.println("PASS: pairing key normalization and malformed input rejection");
  }
}
