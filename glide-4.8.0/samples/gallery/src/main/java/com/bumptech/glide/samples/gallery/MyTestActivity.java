package com.bumptech.glide.samples.gallery;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.widget.ImageView;
import com.bumptech.glide.Glide;

public class MyTestActivity extends Activity {
  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.my_test_activity);



    //普通 图片
    ImageView iv = findViewById(R.id.iv);
    Glide.with(this)
        .load("http://www.baidu.com/img/PCtm_d9c8750bed0b3c7d089fa7d55720d6cf.png")
        .into(iv);

    //gif 图片
    ImageView iv2 = findViewById(R.id.iv2);
    Glide.with(this)
        .load("https://img-blog.csdn.net/20170311214131894?watermark/2/text/aHR0cDovL2Jsb2cuY3Nkbi5uZXQvZ3VvbGluX2Jsb2c=/font/5a6L5L2T/fontsize/400/fill/I0JBQkFCMA==/dissolve/70/gravity/SouthEast")
        .into(iv2);
  }
}
