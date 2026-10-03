package com.evi.live;

public final class PairingKeyTest {
  public static void main(String[] args) {
    String key="abcdef0123456789".repeat(4);
    if(!key.equals(PairingKey.normalize("﻿ "+key.toUpperCase()+"\r\n")))throw new AssertionError("Normalize text-file BOM, whitespace and hex case");
    for(String invalid:new String[]{"", " ", "Scanner key: "+key, key.substring(1), key+"0", "g".repeat(64)}) {
      try {PairingKey.normalize(invalid);throw new AssertionError("Malformed key accepted");}
      catch(IllegalArgumentException expected){}
    }

    // WHEN the plugin looks at plugin-key.txt again. Asserted as a truth table rather than by
    // re-deriving the expression, because a test that restates the condition passes against the bug
    // as well as the fix -- which is how an earlier showAsPaired test proved nothing.
    String other="fedcba9876543210".repeat(4);
    if(!EviLivePlugin.shouldPollForPairingKey(null,false))throw new AssertionError("No key yet: must look (first pairing)");
    if(!EviLivePlugin.shouldPollForPairingKey(null,true))throw new AssertionError("No key and refused: must look");
    if(EviLivePlugin.shouldPollForPairingKey(key,false))throw new AssertionError("A working key must NOT be re-read every tick");
    // THE REGRESSION GUARD. Limiting the poll to pluginKey==null leaves a player who pasted the
    // Scanner key stuck on 401s until they restart RuneLite, even once the app has written the
    // right key -- the exact case auto-pairing exists to end.
    if(!EviLivePlugin.shouldPollForPairingKey(key,true))throw new AssertionError("A REFUSED key must keep looking: the app may have replaced it");

    // WHETHER a key read from that file is adopted.
    if(!EviLivePlugin.shouldAdoptPairingKey(key,null))throw new AssertionError("A key where there was none must be adopted");
    if(!EviLivePlugin.shouldAdoptPairingKey(other,key))throw new AssertionError("A different key must replace the one in use");
    // Load-bearing: while a key is refused this polls every few seconds, and adopting an identical
    // key would call reset() each time, restarting the session on a loop.
    if(EviLivePlugin.shouldAdoptPairingKey(key,key))throw new AssertionError("An identical key must NOT be re-adopted");
    if(EviLivePlugin.shouldAdoptPairingKey(null,key))throw new AssertionError("No key in the file is nothing to adopt");

    System.out.println("PASS: pairing key normalization, malformed input rejection, and when a written key is picked up");
  }
}
