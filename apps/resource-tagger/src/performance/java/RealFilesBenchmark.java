import dev.towertools.resourcetagger.*;
import java.nio.file.*;
public class RealFilesBenchmark {
 public static void main(String[] args)throws Exception {
  String mode=args[0];int n=Integer.parseInt(args[1]);Path base=Path.of(args[2]).toAbsolutePath();Path root=base.resolve("files-"+n);Files.createDirectories(root);
  if(mode.equals("create")){for(int i=0;i<n;i++){Path bucket=root.resolve(String.format("bucket-%06d",i/100+1));Files.createDirectories(bucket);Path p=bucket.resolve(String.format("真实文件-%06d.txt",i));if(!Files.exists(p))Files.createFile(p);}System.out.println("created_files="+n);return;}
  LocalFileSystem fs=new LocalFileSystem();
  for(int i=0;i<3;i++){long t=System.nanoTime();int count=fs.scan(root).size();if(count!=n)throw new AssertionError(count);System.out.printf(java.util.Locale.ROOT,"enumerate%d_ms=%.3f%n",i,(System.nanoTime()-t)/1e6);}
  try(Library lib=new Library(new Database(base.resolve(mode+"-"+n+".sqlite")),fs)){
   String id=lib.saveRoot(null,root.toString(),"Real Files");
   long t=System.nanoTime();lib.scan(id);System.out.printf(java.util.Locale.ROOT,"first_full_scan_ms=%.3f%n",(System.nanoTime()-t)/1e6);
   t=System.nanoTime();lib.scan(id);System.out.printf(java.util.Locale.ROOT,"repeat_full_scan_ms=%.3f%n",(System.nanoTime()-t)/1e6);
   if(lib.snapshot().getResources().size()!=n)throw new AssertionError("Wrong registry count");
  }
 }
}
