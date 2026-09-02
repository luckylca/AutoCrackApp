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
        return Set.of("ui.windows","ui.tree","ui.at","ui.find","ui.props","ui.parent","ui.children","ui.siblings","ui.listeners","ui.stack","ui.image","ui.image.result","ui.action","ui.compose.status").contains(kind);
    }

    public static JSONObject execute(JSONObject request) throws Exception {
        String kind=request.getString("kind");
        return switch(kind){
            case "ui.windows" -> legacy(request,"windows");
            case "ui.tree" -> legacy(request,"view_tree");
            case "ui.at" -> legacy(request,"view_at");
            case "ui.find" -> findViews(request);
            case "ui.action" -> action(request);
            case "ui.props" -> props(request);
            case "ui.parent" -> parent(request);
            case "ui.children" -> children(request);
            case "ui.siblings" -> siblings(request);
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

    private static JSONObject findViews(JSONObject request)throws Exception{
        String text=request.optString("text",""); String resource=request.optString("resource",""); String klass=request.optString("class",request.optString("class_name",""));
        boolean includeHidden=request.optBoolean("include_hidden",false); int max=Math.max(1,Math.min(request.optInt("max_nodes",512),4000));
        JSONArray matches=new JSONArray(); Set<View> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(View root:WindowRegistry.get().snapshot(64)){findWalk(root,text,resource,klass,includeHidden,matches,seen,max);if(matches.length()>=max)break;}
        return ok().put("count",matches.length()).put("truncated",matches.length()>=max).put("matches",matches)
                .put("criteria",new JSONObject().put("text",text).put("resource",resource).put("class",klass).put("include_hidden",includeHidden));
    }

    private static void findWalk(View view,String text,String resource,String klass,boolean includeHidden,JSONArray out,Set<View> seen,int max)throws Exception{
        if(view==null||!seen.add(view)||out.length()>=max)return;
        if(matches(view,text,resource,klass,includeHidden))out.put(brief(view,relationIndex(view),-1));
        if(view instanceof ViewGroup group){for(int i=0;i<group.getChildCount()&&out.length()<max;i++)findWalk(group.getChildAt(i),text,resource,klass,includeHidden,out,seen,max);}
    }

    private static boolean matches(View view,String text,String resource,String klass,boolean includeHidden){
        if(!includeHidden&&(!view.isShown()||view.getAlpha()<=0f))return false;
        if(text!=null&&!text.isBlank()){String haystack="";try{haystack+=(view.getContentDescription()==null?"":view.getContentDescription().toString())+"\n";}catch(Throwable ignored){}if(view instanceof TextView tv){haystack+=String.valueOf(tv.getText())+"\n"+(tv.getHint()==null?"":tv.getHint().toString());}if(!haystack.contains(text))return false;}
        if(resource!=null&&!resource.isBlank()){Object res=resourceName(view);if(res==JSONObject.NULL||!String.valueOf(res).contains(resource))return false;}
        if(klass!=null&&!klass.isBlank()&&!view.getClass().getName().contains(klass)&&!view.getClass().getSimpleName().contains(klass))return false;
        return true;
    }

    private static JSONObject props(JSONObject request)throws Exception{
        View view=requireView(request); JSONObject value=base(view);
        value.put("screen_bounds",boundsOnScreen(view)).put("window_bounds",boundsInWindow(view))
                .put("local_bounds",new JSONArray().put(0).put(0).put(view.getWidth()).put(view.getHeight()))
                .put("scroll",new JSONArray().put(view.getScrollX()).put(view.getScrollY()))
                .put("padding",new JSONArray().put(view.getPaddingLeft()).put(view.getPaddingTop()).put(view.getPaddingRight()).put(view.getPaddingBottom()))
                .put("clip_to_outline",Build.VERSION.SDK_INT>=21 && view.getClipToOutline())
                .put("has_focus",view.hasFocus()).put("focused",view.isFocused())
                .put("selected",view.isSelected()).put("pressed",view.isPressed())
                .put("activated",view.isActivated()).put("layout_direction",view.getLayoutDirection())
                .put("important_for_accessibility",view.getImportantForAccessibility())
                .put("content_description",view.getContentDescription()==null?JSONObject.NULL:cut(String.valueOf(view.getContentDescription())))
                .put("tag",view.getTag()==null?JSONObject.NULL:cut(String.valueOf(view.getTag())));
        View parent = view.getParent() instanceof View pv ? pv : null;
        value.put("parent", parent==null?JSONObject.NULL:brief(parent, -1, relationIndex(parent)));
        View root = view.getRootView();
        value.put("root", root==null?JSONObject.NULL:brief(root, -1, relationIndex(root)));
        if(view instanceof ViewGroup group)value.put("viewgroup",new JSONObject().put("child_count",group.getChildCount()).put("clip_children",group.getClipChildren()).put("clip_to_padding",group.getClipToPadding()));
        ViewGroup.LayoutParams lp=view.getLayoutParams();
        if(lp!=null){JSONObject layout=new JSONObject().put("class",lp.getClass().getName()).put("width",lp.width).put("height",lp.height);if(lp instanceof ViewGroup.MarginLayoutParams margin)layout.put("margin",new JSONArray().put(margin.leftMargin).put(margin.topMargin).put(margin.rightMargin).put(margin.bottomMargin));value.put("layout_params",layout);}
        value.put("background",drawable(view.getBackground())); if(Build.VERSION.SDK_INT>=23)value.put("foreground",drawable(view.getForeground()));
        if(view instanceof TextView text)value.put("textview",new JSONObject().put("text",cut(String.valueOf(text.getText()))).put("hint",text.getHint()==null?JSONObject.NULL:cut(String.valueOf(text.getHint()))).put("text_size_px",text.getTextSize()).put("text_color",text.getCurrentTextColor()).put("hint_color",text.getCurrentHintTextColor()).put("selection_start",text.getSelectionStart()).put("selection_end",text.getSelectionEnd()).put("editable",text.getEditableText()!=null).put("input_type",text.getInputType()).put("single_line",text.isSingleLine()));
        if(view instanceof ImageView image)value.put("imageview",new JSONObject().put("drawable",drawable(image.getDrawable())).put("scale_type",String.valueOf(image.getScaleType())).put("adjust_view_bounds",image.getAdjustViewBounds()).put("image_matrix",image.getImageMatrix()==null?JSONObject.NULL:String.valueOf(image.getImageMatrix())));
        if(view instanceof AdapterView<?> adapter){Object a=adapter.getAdapter();value.put("adapterview",new JSONObject().put("count",adapter.getCount()).put("first_visible_position",adapter.getFirstVisiblePosition()).put("last_visible_position",adapter.getLastVisiblePosition()).put("selected_position",adapter.getSelectedItemPosition()).put("adapter_class",a==null?JSONObject.NULL:a.getClass().getName()).put("adapter_handle",a==null?JSONObject.NULL:ObjectRegistry.get().put(a,false,"adapter")));}
        if(view instanceof VideoView video){JSONObject vv=new JSONObject();vv.put("uri",nullable(fieldText(video,"mUri"))).put("headers",nullable(fieldText(video,"mHeaders")));value.put("videoview",vv);}
        if(view instanceof WebView web){JSONObject w=new JSONObject().put("url",nullable(safeString(web::getUrl))).put("title",nullable(safeString(web::getTitle))).put("progress",safeInt(web::getProgress,-1));try{w.put("user_agent",web.getSettings().getUserAgentString()).put("javascript_enabled",web.getSettings().getJavaScriptEnabled()).put("dom_storage_enabled",web.getSettings().getDomStorageEnabled()).put("mixed_content_mode",Build.VERSION.SDK_INT>=21?web.getSettings().getMixedContentMode():JSONObject.NULL);}catch(Throwable e){w.put("settings_error",e.toString());}value.put("webview",w);}
        return ok().put("view",value);
    }

    private static JSONObject parent(JSONObject request)throws Exception{
        View view=requireView(request); View parent=view.getParent() instanceof View pv?pv:null;
        return ok().put("view",brief(view, relationIndex(view), -1)).put("parent",parent==null?JSONObject.NULL:brief(parent, relationIndex(parent), -1));
    }

    private static JSONObject children(JSONObject request)throws Exception{
        View view=requireView(request); int max=Math.max(1,Math.min(request.optInt("max_children",256),2048)); JSONArray out=new JSONArray();
        if(view instanceof ViewGroup group){for(int i=0;i<group.getChildCount()&&i<max;i++)out.put(brief(group.getChildAt(i),i,relationIndex(group.getChildAt(i))));}
        return ok().put("view",brief(view,relationIndex(view),-1)).put("count",out.length()).put("truncated",view instanceof ViewGroup g && g.getChildCount()>out.length()).put("children",out);
    }

    private static JSONObject siblings(JSONObject request)throws Exception{
        View view=requireView(request); View parent=view.getParent() instanceof View pv?pv:null; JSONArray out=new JSONArray(); int index=-1;
        if(parent instanceof ViewGroup group){for(int i=0;i<group.getChildCount();i++){View child=group.getChildAt(i);if(child==view)index=i;out.put(brief(child,i,relationIndex(child)));}}
        return ok().put("view",brief(view,index,-1)).put("parent",parent==null?JSONObject.NULL:brief(parent,relationIndex(parent),-1)).put("index",index).put("count",out.length()).put("siblings",out);
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
            case "set_enabled" -> view.setEnabled(action.optBoolean("value",true));
            case "set_clickable" -> view.setClickable(action.optBoolean("value",true));
            case "set_long_clickable" -> view.setLongClickable(action.optBoolean("value",true));
            case "request_focus" -> {if(!view.requestFocus())return error("FOCUS_REJECTED","requestFocus returned false");}
            case "clear_focus" -> view.clearFocus();
            case "invalidate" -> view.invalidate();
            case "remove", "remove_view" -> {if(!(view.getParent() instanceof ViewGroup parent))return error("NO_PARENT","View parent is not ViewGroup");parent.removeView(view);}
            case "click", "perform_click" -> {if(!view.performClick())return error("CLICK_REJECTED","performClick returned false");}
            case "set_alpha" -> view.setAlpha((float)action.getDouble("value"));
            case "set_size" -> {ViewGroup.LayoutParams lp=view.getLayoutParams();if(lp==null)return error("NO_LAYOUT_PARAMS","View has no LayoutParams");if(action.has("width"))lp.width=action.getInt("width");if(action.has("height"))lp.height=action.getInt("height");view.setLayoutParams(lp);}
            case "set_padding" -> view.setPadding(action.getInt("left"),action.getInt("top"),action.getInt("right"),action.getInt("bottom"));
            case "set_margin" -> {if(!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp))return error("NO_MARGIN_LAYOUT_PARAMS","LayoutParams do not support margins");lp.setMargins(action.getInt("left"),action.getInt("top"),action.getInt("right"),action.getInt("bottom"));view.setLayoutParams(lp);}
            case "set_background_color" -> view.setBackgroundColor(action.getInt("value"));
            case "set_text" -> {if(!(view instanceof TextView text))return typeMismatch("TextView");text.setText(action.optString("value",""));}
            case "append_text" -> {if(!(view instanceof TextView text))return typeMismatch("TextView");text.append(action.optString("value",""));}
            case "set_hint" -> {if(!(view instanceof TextView text))return typeMismatch("TextView");text.setHint(action.optString("value",""));}
            case "set_text_size" -> {if(!(view instanceof TextView text))return typeMismatch("TextView");text.setTextSize((float)action.getDouble("value_sp"));}
            case "set_text_color" -> {if(!(view instanceof TextView text))return typeMismatch("TextView");text.setTextColor(action.getInt("value"));}
            case "set_hint_color" -> {if(!(view instanceof TextView text))return typeMismatch("TextView");text.setHintTextColor(action.getInt("value"));}
            case "image_set_resource" -> {if(!(view instanceof ImageView image))return typeMismatch("ImageView");image.setImageResource(action.getInt("resource_id"));}
            case "image_clear" -> {if(!(view instanceof ImageView image))return typeMismatch("ImageView");image.setImageDrawable(null);}
            case "webview_load_url" -> {if(!(view instanceof WebView web))return typeMismatch("WebView");web.loadUrl(action.getString("url"));}
            case "webview_user_agent" -> {if(!(view instanceof WebView web))return typeMismatch("WebView");web.getSettings().setUserAgentString(action.getString("value"));}
            case "webview_eval", "webview_eval_js" -> {if(!(view instanceof WebView web))return typeMismatch("WebView");web.evaluateJavascript(action.getString("script"),null);}
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
    private static JSONObject base(View v)throws Exception{return new JSONObject().put("handle",ObjectRegistry.get().put(v,false,"ui")).put("class",v.getClass().getName()).put("simple_class",v.getClass().getSimpleName()).put("id",v.getId()).put("resource_name",resourceName(v)).put("width",v.getWidth()).put("height",v.getHeight()).put("visibility",v.getVisibility()).put("shown",v.isShown()).put("attached",v.isAttachedToWindow()).put("enabled",v.isEnabled()).put("clickable",v.isClickable()).put("long_clickable",v.isLongClickable()).put("focusable",v.isFocusable()).put("translation_x",v.getTranslationX()).put("translation_y",v.getTranslationY()).put("scale_x",v.getScaleX()).put("scale_y",v.getScaleY()).put("rotation",v.getRotation()).put("rotation_x",v.getRotationX()).put("rotation_y",v.getRotationY()).put("elevation",v.getElevation()).put("z",v.getZ()).put("alpha",v.getAlpha());}
    private static JSONObject brief(View v,int index,int relationIndex)throws Exception{return base(v).put("index",index).put("relation_index",relationIndex).put("screen_bounds",boundsOnScreen(v));}
    private static int relationIndex(View v){if(!(v.getParent() instanceof ViewGroup group))return -1;for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i)==v)return i;return -1;}
    private static JSONArray boundsOnScreen(View v){JSONArray out=new JSONArray();int[] loc=new int[2];try{v.getLocationOnScreen(loc);return out.put(loc[0]).put(loc[1]).put(loc[0]+v.getWidth()).put(loc[1]+v.getHeight());}catch(Throwable e){return out.put(0).put(0).put(0).put(0);}}
    private static JSONArray boundsInWindow(View v){JSONArray out=new JSONArray();int[] loc=new int[2];try{v.getLocationInWindow(loc);return out.put(loc[0]).put(loc[1]).put(loc[0]+v.getWidth()).put(loc[1]+v.getHeight());}catch(Throwable e){return out.put(0).put(0).put(0).put(0);}}
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
    private static int safeInt(IntSupplier s,int fallback){try{return s.get();}catch(Throwable e){return fallback;}}
    private static JSONObject ok()throws Exception{return new JSONObject().put("ok",true);}private static JSONObject error(String c,String m)throws Exception{return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",c).put("message",m));}
    private interface StringSupplier{String get()throws Throwable;}private interface IntSupplier{int get()throws Throwable;}private record AsyncImage(boolean done,JSONObject image,String error,long createdAt){}
}
