package com.xianghuanji.test;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import java.io.File;

public class MyTestActivity extends Activity {
  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.my_test_activity);

    //gif 图片
//    ImageView iv2 = findViewById(R.id.iv2);
//    Glide.with(this)
//        .load(
//            "https://img-blog.csdn.net/20170311214131894?watermark/2/text/aHR0cDovL2Jsb2cuY3Nkbi5uZXQvZ3VvbGluX2Jsb2c=/font/5a6L5L2T/fontsize/400/fill/I0JBQkFCMA==/dissolve/70/gravity/SouthEast")
//        .into(iv2);



    View btn = findViewById(R.id.btn);
    btn.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        //普通 图片
        ImageView iv = findViewById(R.id.iv);
        Glide.with(MyTestActivity.this)
            .asBitmap()
            .load("http://www.baidu.com/img/PCtm_d9c8750bed0b3c7d089fa7d55720d6cf.png?time="+SystemClock.currentThreadTimeMillis())
//            .load("http://www.baidu.com/img/PCtm_d9c8750bed0b3c7d089fa7d55720d6cf.png")
            .into(iv);
      }
    });

    View btn1 = findViewById(R.id.btn1);
    btn1.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        //普通 图片
        ImageView iv = findViewById(R.id.iv);

        RequestOptions sharedOptions = new RequestOptions()
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE);

        Glide.with(MyTestActivity.this)
            .load("http://www.baidu.com/img/PCtm_d9c8750bed0b3c7d089fa7d55720d6cf.png")
            .apply(sharedOptions)
            .into(iv);
      }
    });

    findViewById(R.id.btn2).setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View view) {
        new Thread(new Runnable() {
          @Override
          public void run() {
            Glide.get(MyTestActivity.this).clearDiskCache();
          }
        }).start();
        Glide.get(MyTestActivity.this).clearMemory();
      }
    });

    View btn3 = findViewById(R.id.btn3);
    btn3.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        //普通 图片
        ImageView iv = findViewById(R.id.iv3);

        File file = new File(Environment.getExternalStorageDirectory(), "test.jpg");

        Glide.with(MyTestActivity.this)
            .load(R.mipmap.tu1)
            .into(iv);
      }
    });
  }
}
