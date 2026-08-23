package com.quicklink.app.link;


public final class Base62 {
     private static final String ALPHABET =
       "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
     public static String encode(long n) {
       if (n == 0) return "0";
       StringBuilder sb = new StringBuilder();
       while (n > 0) { sb.append(ALPHABET.charAt((int)(n % 62))); n /= 62; }
       return sb.reverse().toString();
     }
   }