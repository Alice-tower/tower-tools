import dev.towertools.resourcetagger.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.lang.management.*;
public class Benchmark {
 static Object heldView;
 static void measure(String name, Runnable action) { long t=System.nanoTime(); action.run(); System.out.printf(java.util.Locale.ROOT,"%s_ms=%.3f%n", name,(System.nanoTime()-t)/1e6); System.out.flush(); }
 static Query query(boolean review) { return new Query("",null,null,null,false,review,Map.of()); }
 static class Fs implements ResourceFileSystem {
  List<Found> items;
  Fs(int n) { items=new ArrayList<>(); for(int i=0;i<n;i++)items.add(new Found(String.format("bucket-%06d/资源-%06d.txt",i/100+1,i),Kind.File)); }
  public List<Found> scan(Path p){return items;}
  public Kind inspect(Path p){return Kind.File;}
 }
 static Object page(Library lib, Query q) {
  try { return lib.getClass().getMethod("queryPage", Query.class,int.class,int.class).invoke(lib,q,0,200); }
  catch(NoSuchMethodException e){Snapshot state=lib.snapshot();return new Object[]{state,q.apply(state)};}
  catch(Exception e){throw new RuntimeException(e);}
 }
 public static void main(String[] args)throws Exception {
  String mode=args[0]; int n=Integer.parseInt(args[1]); Path base=Path.of(args[2]).toAbsolutePath();Files.createDirectories(base);
  Path dbPath=base.resolve("library.sqlite"); Path root=base.resolve("root"); Files.createDirectories(root);
  if(mode.equals("seed")) {
   try(Library lib=new Library(new Database(dbPath),new Fs(n))){lib.saveRoot(null,root.toString(),"Benchmark");}
   try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+dbPath)) {
    org.sqlite.Function.create(db,"normalize_name",new org.sqlite.Function(){protected void xFunc()throws SQLException{result(ModelsKt.normalizedName(value_text(0)));}},1,org.sqlite.Function.FLAG_DETERMINISTIC);
    org.sqlite.Collation.create(db,"JAVA_TEXT",new org.sqlite.Collation(){protected int xCompare(String a,String b){return a.compareTo(b);}}); db.setAutoCommit(false);String rootId;try(ResultSet r=db.createStatement().executeQuery("SELECT id FROM roots")){r.next();rootId=r.getString(1);}
    try(PreparedStatement resources=db.prepareStatement("INSERT INTO resources(id,root_id,relative_path,normalized_relative_path,kind,display_name,status,created_at,last_seen_at) VALUES(?,?,?,?, 'File',?,'Active','2026-01-01','2026-01-01')");PreparedStatement review=db.prepareStatement("INSERT INTO review_items(id,resource_id,reason,state,detected_at) VALUES(?,?,'New','Pending','2026-01-01')");PreparedStatement links=db.prepareStatement("INSERT INTO resource_tags VALUES(?,?)")) {
     for(int i=0;i<20;i++){db.createStatement().executeUpdate("INSERT INTO tags VALUES('t"+i+"','2026-01-01')");db.createStatement().executeUpdate("INSERT INTO tag_names VALUES('tn"+i+"','t"+i+"','tag"+i+"','tag"+i+"','canonical')");}
     for(int i=0;i<n;i++){String id="r"+i,name=String.format("资源-%06d.txt",i);resources.setString(1,id);resources.setString(2,rootId);resources.setString(3,String.format("bucket-%06d/",i/100+1)+name);resources.setString(4,String.format("bucket-%06d/",i/100+1)+name);resources.setString(5,name);resources.addBatch();review.setString(1,"v"+i);review.setString(2,id);review.addBatch();for(int j=0;j<3;j++){links.setString(1,id);links.setString(2,"t"+((i+j)%20));links.addBatch();}}
     resources.executeBatch();review.executeBatch();links.executeBatch();
    } db.commit();
   }System.out.println("seeded="+n);return;
  }
  Fs fs=new Fs(n);try(Library lib=new Library(new Database(dbPath),fs)) {
   if(mode.equals("missing")){fs.items=List.of();String rootId=lib.snapshot().getRoots().getFirst().getId();measure("all_missing",()->lib.scan(rootId));return;}
   String rootId=lib.snapshot().getRoots().getFirst().getId();
   // One warm-up, then five measured query runs. Scan uses the same synthetic enumeration for both versions.
   page(lib,query(false));page(lib,query(true));
   for(int i=0;i<5;i++){measure("browse"+i,()->page(lib,query(false)));measure("review"+i,()->page(lib,query(true)));measure("filter"+i,()->page(lib,new Query("资源-0",null,null,null,false,false,Map.of("t1",TagFilter.Include,"t2",TagFilter.Include,"t5",TagFilter.Exclude))));}
   measure("unchanged_scan",()->lib.scan(rootId));
   measure("single_tag_and_refresh",()->{lib.setTag(Set.of("r0"),"t10",true);try{lib.getClass().getMethod("overview").invoke(lib);}catch(NoSuchMethodException ignored){}catch(Exception e){throw new RuntimeException(e);} page(lib,query(false));});
   fs.items=List.of(); Object metadata=null; try{metadata=lib.getClass().getMethod("overview").invoke(lib);}catch(NoSuchMethodException ignored){} heldView=new Object[]{page(lib,query(false)),metadata}; System.gc();Thread.sleep(150);
   System.out.printf(java.util.Locale.ROOT,"retained_view_heap_mib=%.3f%n",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()/1048576.0);
   double peak=ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum()/1048576.0;
   System.out.printf(java.util.Locale.ROOT,"heap_pool_peak_sum_mib=%.3f%n",peak);
  }
 }
}
