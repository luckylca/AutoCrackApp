package com.luckylca.autocrack.runtime.shared;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Base64;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;
import com.luckylca.runtimeinspector.runtime.InspectorPrimitives;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android View inspection, listener, creation-stack, image and live-mutation capabilities. */
public final class UiIntrospector {
    private static final int MAX_IMAGE_PIXELS = 16_000_000;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final ConcurrentHashMap<String, AsyncImage> IMAGES = new ConcurrentHashMap<>();
    private UiIntrospector() {}

    public static boolean supports(String kind) {
        return Set.of("ui.windows","ui.tree","ui.at","ui.props","ui.listeners","ui.stack","ui.image","ui.image.result","ui.action","ui.compose.status").contains(kind);
    }

    public static JSONObject execute(JSONObject request) throws Exception {
        String kind=request.getString("kind");
        return switch(kind){
            case "ui.windows" -> legacy(request,"windows");
            case "ui.tree" -> legacy(request,"view_tree");
            case "ui.at" -> legacy(request,"view_at");
            case "ui.action" -> action(request);
            case "ui.props" -> props(request);
            case "ui.listeners" -> listeners(request);
            case "ui.stack" -> stacks(request);
            case "ui.image" -> image(request);
            case "ui.image.result" -> imageResult(request);
            case "ui.compose.status" -> composeStatus();
            default -> error("UNSUPPORTED_KIND",kind);
        };
    }

    private static JSONObject legacy(JSONObject input,String kind)throws Exception{
        JSONObject request=new JSONObject(input.toString()).put("kind",kind);
        return InspectorPrimitives.execute(request);
    }

    private static JSONObject props(JSONObject request)throws Exception{
        View view=requireView(request); JSONObject value=base(view);
        value.put("padding",new JSONArray().put(view.getPaddingLeft()).put(view.getPaddingTop()).put(view.getPaddingRight()).put(view.getPaddingBottom()));
        ViewGroup.LayoutParams lp=view.getLayoutParams();
        if(lp!=null){JSONObject layout=new JSONObject().put("class",lp.getClass().getName()).put("width",lp.width).put("height",lp.height);if(lp instanceof ViewGroup.MarginLayoutParams margin)layout.put("margin",new JSONArray().put(margin.leftMargin).put(margin.topMargin).put(margin.rightMargin).put(margin.bottomMargin));value.put("layout_params",layout);}
        value.put("background",drawable(view.getBackground())); if(Build.VERSION.SDK_INT>=23)value.put("foreground",drawable(view.getForeground()));
        if(view instanceof TextView text)value.put("textview",new JSONObject().put("text",cut(String.valueOf(text.getText()))).put("hint",text.getHint()==null?JSONObject.NULL:cut(String.valueOf(text.getHint()))).put("text_size_px",text.getTextSize()).put("text_color",text.getCurrentTextColor()).put("hint_color",text.getCurrentHintTextColor()));
        if(view instanceof ImageView image)value.put("imageview",new JSONObject().put("drawable",drawable(image.getDrawable())).put("scale_type",String.valueOf(image.getScaleType())));
        if(view instanceof AdapterView<?> adapter){Object a=adapter.getAdapter();value.put("adapterview",new JSONObject().put("count",adapter.getCount()).put("selected_position",adapter.getSelectedItemPosition()).put("adapter_class",a==null?JSONObject.NULL:a.getClass().getName()).put("adapter_handle",a==null?JSONObject.NULL:ObjectRegistry.get().put(a,false,"adapter")));}
        if(view instanceof VideoView video){JSONObject vv=new JSONObject();vv.put("uri",nullable(fieldText(video,"mUri"))).put("headers",nullable(fieldText(video,"mHeaders")));value.put("videoview",vv);}
        if(view instanceof WebView web){JSONObject w=new JSONObject().put("url",nullable(safeString(web::getUrl)));try{w.put("user_agent",web.getSettings().getUserAgentString()).put("javascript_enabled",web.getSettings().getJavaScriptEnabled());}catch(Throwable e){w.put("settings_error",e.toString());}value.put("webview",w);}
        return ok().put("view",value);
    }

    private static JSONObject listeners(JSONObject request)throws Exception{
        View view=requireView(request); JSONArray values=new JSONArray();
        Object info=readField(view,"mListenerInfo");
        if(info!=null){for(String name:new String[]{"mOnClickListener","mOnLongClickListener","mOnTouchListener","mOnKeyListener","mOnFocusChangeListener","mOnHoverListener","mOnDragListener","mOnContextClickListener"})addListener(values,"View.ListenerInfo."+name,readField(info,name));}
        if(view instanceof AdapterView<?>){for(String name:new String[]{"mOnItemClickListener","mOnItemLongClickListener","mOnItemSelectedListener"})addListener(values,"AdapterView."+name,readField(view,name));}
        if(view instanceof TextView){Object watchers=readField(view,"mListeners");if(watchers instanceof Iterable<?> iterable){int i=0;for(Object item:iterable){if(i++>=64)break;addListener(values,"TextView.TextWatcher["+(i-1)+"]",item);}}}
        return ok().put("view_handle",ObjectRegistry.get().put(view,false,"ui")).put("count",values.length()).put("listeners",values)
                .put("hidden_api_strategy","reflection in LSPosed target process; per-field errors are isolated");
    }

    private static void addListener(JSONArray out,String source,Object listener)throws Exception{
        if(listener==null)return;JSONObject item=new JSONObject().put("source",source).put("class",listener.getClass().getName()).put("handle",ObjectRegistry.get().put(listener,false,"listener"));
        JSONArray methods=new JSONArray();for(Method method:listener.getClass().getDeclaredMethods()){if(methods.length()>=64)break;JSONArray params=new JSONArray();for(Class<?> p:method.getParameterTypes())params.put(p.getName());methods.put(new JSONObject().put("class",listener.getClass().getName()).put("name",method.getName()).put("parameters",params).put("return_type",method.getReturnType().getName()).put("static",Modifier.isStatic(method.getModifiers())));}
        item.put("methods",methods);out.put(item);
    }

    private static JSONObject stacks(JSONObject request)throws Exception{
        View view=requireView(request);ViewCreationTracker.Record r=ViewCreationTracker.get().get(view);if(r==null)return ok().put("view_handle",ObjectRegistry.get().put(view,false,"ui")).put("available",false).put("reason","View predates tracker installation or was not observed");
        return ok().put("view_handle",ObjectRegistry.get().put(view,false,"ui")).put("available",true)
                .put("construction",stack(r.construction())).put("inflate",stack(r.inflate())).put("add",stack(r.add()));
    }

    private static JSONObject image(JSONObject request)throws Exception{
        View view=requireView(request);int width=view.getWidth(),height=view.getHeight();if(width<=0||height<=0)return error("EMPTY_VIEW","View has no drawable size");if((long)width*height>MAX_IMAGE_PIXELS)return error("IMAGE_TOO_LARGE","View exceeds max pixel budget");
        if(view instanceof SurfaceView){Window window=findWindow(view);if(window==null)return unsupportedImage("SurfaceView requires PixelCopy but owning Window was not found");Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);int[] location=new int[2];view.getLocationInWindow(location);Rect rect=new Rect(location[0],location[1],location[0]+width,location[1]+height);String token="img_"+UUID.randomUUID().toString().replace("-","");IMAGES.put(token,new AsyncImage(false,null,null,System.currentTimeMillis()));PixelCopy.request(window,rect,bitmap,result->{if(result==PixelCopy.SUCCESS){try{IMAGES.put(token,new AsyncImage(true,encode(bitmap),null,System.currentTimeMillis()));}catch(Throwable e){IMAGES.put(token,new AsyncImage(true,null,e.toString(),System.currentTimeMillis()));}}else IMAGES.put(token,new AsyncImage(true,null,"PixelCopy result="+result,System.currentTimeMillis()));bitmap.recycle();},new android.os.Handler(android.os.Looper.getMainLooper()));return ok().put("pending",true).put("token",token).put("strategy","PixelCopy(Window)");}
        Bitmap bitmap;
        String strategy;
        if(view instanceof TextureView texture){bitmap=texture.getBitmap();strategy="TextureView.getBitmap";if(bitmap==null)return error("BITMAP_FAILED","TextureView returned null bitmap");}
        else{bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);view.draw(canvas);strategy="View.draw(Canvas)";}
        try{return ok().put("pending",false).put("strategy",strategy).put("image",encode(bitmap));}finally{bitmap.recycle();}
    }

    private static JSONObject imageResult(JSONObject request)throws Exception{String token=request.getString("token");AsyncImage value=IMAGES.get(token);if(value==null)return error("IMAGE_NOT_FOUND",token);if(!value.done)return ok().put("pending",true).put("token",token);IMAGES.remove(token);if(value.error!=null)return error("PIXEL_COPY_FAILED",value.error);return ok().put("pending",false).put("token",token).put("image",value.image);}

    private static JSONObject action(JSONObject request)throws Exception{
        View view=requireView(request);JSONObject action=request.getJSONObject("action");String type=action.getString("type");
        switch(type){
            case "set_visibility" -> view.setVisibility(switch(action.optString("value","visible")){case "gone"->View.GONE;case "invisible"->View.INVISIBLE;default->View.VISIBLE;});
            case "remove" -> {if(!(view.getParent() instanceof ViewGroup parent))return error("NO_PARENT","View parent is not ViewGroup");parent.removeView(view);}
            case "click" -> {if(!view.performClick())return error("CLICK_REJECTED","performClick returned false");}
            case "set_alpha" -> view.setAlpha((float)action.getDouble("value"));
            case "set_size" -> {ViewGroup.LayoutParams lp=view.getLayoutParams();if(lp==null)return error("NO_LAYOUT_PARAMS","View has no LayoutParams");if(action.has("width"))lp.width=action.getInt("width");if(action.has("height"))lp.height=action.getInt("height");view.setLayoutParams(lp);}
            case "set_padding" -> view.setPadding(action.getInt("left"),action.getInt("top"),action.getInt("right"),action.getInt("bottom"));
            case "set_margin" -> {if(!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp))return error("NO_MARGIN_LAYOUT_PARAMS","LayoutParams do not support margins");lp.setMargins(action.getInt("left"),action.getInt("top"),action.getInt("right"),action.getInt("bottom"));view.setLayoutParams(lp);}
            case "set_background_color" -> view.setBackgroundColor(action.getInt("value"));
            case "set_text" -> {if(!(view instanceof TextView text))return typeMismatch("TextView");text.setText(action.optString("value",""));}
            case "set_text_size" -> {if(!(view instanceof TextView text))return typeMismatch("TextView");text.setTextSize((float)action.getDouble("value_sp"));}
            case "set_text_color" -> {if(!(view instanceof TextView text))return typeMismatch("TextView");text.setTextColor(action.getInt("value"));}
            case "set_hint_color" -> {if(!(view instanceof TextView text))return typeMismatch("TextView");text.setHintTextColor(action.getInt("value"));}
            case "image_set_resource" -> {if(!(view instanceof ImageView image))return typeMismatch("ImageView");image.setImageResource(action.getInt("resource_id"));}
            case "webview_load_url" -> {if(!(view instanceof WebView web))return typeMismatch("WebView");web.loadUrl(action.getString("url"));}
            case "webview_user_agent" -> {if(!(view instanceof WebView web))return typeMismatch("WebView");web.getSettings().setUserAgentString(action.getString("value"));}
            case "webview_eval" -> {if(!(view instanceof WebView web))return typeMismatch("WebView");web.evaluateJavascript(action.getString("script"),null);}
            default -> {return error("UNSUPPORTED_ACTION",type);}
        }
        return ok().put("handle",ObjectRegistry.get().put(view,false,"ui")).put("action",type).put("persistent",false);
    }

    private static JSONObject composeStatus()throws Exception{
        JSONArray roots=new JSONArray();Set<View> seen=Collections.newSetFromMap(new IdentityHashMap<>());for(View root:WindowRegistry.get().snapshot(64))findCompose(root,roots,seen);
        boolean present=roots.length()>0;return ok().put("android_compose_view_count",roots.length()).put("android_compose_views",roots)
                .put("semantics_tree_supported",false)
                .put("reason",present?"AndroidComposeView is identified, but Compose Semantics internals vary by Compose runtime version and are not exposed as fake Android View children.":"No AndroidComposeView observed in current windows");
    }
    private static void findCompose(View v,JSONArray out,Set<View> seen)throws Exception{if(v==null||!seen.add(v)||out.length()>=64)return;if(v.getClass().getName().endsWith("AndroidComposeView"))out.put(base(v));if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)findCompose(g.getChildAt(i),out,seen);}

    private static View requireView(JSONObject request){String handle=request.optString("handle","");Object value=ObjectRegistry.get().get(handle);if(value instanceof View view)return view;throw new IllegalArgumentException("STALE_OR_NON_VIEW_HANDLE:"+handle);}
    private static JSONObject base(View v)throws Exception{return new JSONObject().put("handle",ObjectRegistry.get().put(v,false,"ui")).put("class",v.getClass().getName()).put("id",v.getId()).put("resource_name",resourceName(v)).put("width",v.getWidth()).put("height",v.getHeight()).put("visibility",v.getVisibility()).put("shown",v.isShown()).put("attached",v.isAttachedToWindow()).put("translation_x",v.getTranslationX()).put("translation_y",v.getTranslationY()).put("scale_x",v.getScaleX()).put("scale_y",v.getScaleY()).put("rotation",v.getRotation()).put("elevation",v.getElevation()).put("z",v.getZ());}
    private static Object resourceName(View v){if(v.getId()==View.NO_ID)return JSONObject.NULL;try{return v.getResources().getResourceName(v.getId());}catch(Throwable e){return Integer.toString(v.getId());}}
    private static Object drawable(Drawable d)throws Exception{return d==null?JSONObject.NULL:new JSONObject().put("class",d.getClass().getName()).put("text",cut(String.valueOf(d))).put("handle",ObjectRegistry.get().put(d,false,"drawable"));}
    private static String fieldText(Object o,String name){try{Object v=readField(o,name);return v==null?null:String.valueOf(v);}catch(Throwable e){return null;}}
    private static Object readField(Object o,String name)throws Exception{Class<?> c=o.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){c=c.getSuperclass();}}return null;}
    private static JSONArray stack(StackTraceElement[] values){JSONArray out=new JSONArray();if(values!=null)for(StackTraceElement v:values)out.put(v.toString());return out;}
    private static Window findWindow(View view){for(ActivityRegistry.ActivitySnapshot s:ActivityRegistry.get().snapshot()){Window w=s.activity().getWindow();if(w!=null&&contains(w.getDecorView(),view))return w;}return null;}
    private static boolean contains(View root,View target){if(root==target)return true;if(root instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)if(contains(g.getChildAt(i),target))return true;return false;}
    private static JSONObject encode(Bitmap bitmap)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);byte[] bytes=out.toByteArray();if(bytes.length>MAX_IMAGE_BYTES)throw new IllegalArgumentException("PNG exceeds inline image byte budget");return new JSONObject().put("width",bitmap.getWidth()).put("height",bitmap.getHeight()).put("format","png").put("encoding","base64").put("size",bytes.length).put("data",Base64.encodeToString(bytes,Base64.NO_WRAP));}
    private static JSONObject unsupportedImage(String reason)throws Exception{return ok().put("supported",false).put("reason",reason).put("strategies",new JSONArray().put("View.draw(Canvas)").put("TextureView.getBitmap").put("PixelCopy(Window)"));}
    private static JSONObject typeMismatch(String expected)throws Exception{return error("TYPE_MISMATCH","Target is not "+expected);}
    private static Object nullable(String v){return v==null?JSONObject.NULL:v;}private static String cut(String v){return v!=null&&v.length()>2048?v.substring(0,2048):v;}private static String safeString(StringSupplier s){try{return s.get();}catch(Throwable e){return null;}}
    private static JSONObject ok()throws Exception{return new JSONObject().put("ok",true);}private static JSONObject error(String c,String m)throws Exception{return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",c).put("message",m));}
    private interface StringSupplier{String get()throws Throwable;}private record AsyncImage(boolean done,JSONObject image,String error,long createdAt){}
}
