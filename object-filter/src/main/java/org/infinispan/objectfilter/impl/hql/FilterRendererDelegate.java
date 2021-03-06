package org.infinispan.objectfilter.impl.hql;

import org.antlr.runtime.tree.Tree;
import org.hibernate.hql.ast.origin.hql.resolve.path.PropertyPath;
import org.hibernate.hql.ast.spi.SingleEntityQueryBuilder;
import org.hibernate.hql.ast.spi.SingleEntityQueryRendererDelegate;
import org.infinispan.objectfilter.impl.syntax.BooleanExpr;

import java.util.Map;

/**
 * @author anistor@redhat.com
 * @since 7.0
 */
public class FilterRendererDelegate<TypeMetadata> extends SingleEntityQueryRendererDelegate<BooleanExpr, FilterParsingResult> {

   private final ObjectPropertyHelper<TypeMetadata> propertyHelper;

   /**
    * Optional {@code Comparator} that corresponds to the 'order by' clause.
    */
   //private PropsComparator comparator;

   private TypeMetadata targetEntityMetadata;

   public FilterRendererDelegate(ObjectPropertyHelper propertyHelper, SingleEntityQueryBuilder<BooleanExpr> builder, Map<String, Object> namedParameters) {
      super(propertyHelper.getEntityNamesResolver(), builder, namedParameters);
      this.propertyHelper = propertyHelper;
   }

   //@Override  TODO switch to latest hql parser to be able to use sorting
   protected void addSortField(PropertyPath propertyPath, String collateName, boolean isAscending) {
//      if (comparator == null) {
//         comparator = new PropsComparator();
//      }
//      comparator.addSort(new PropertyValueExpr(propertyPath.asStringPathWithoutAlias()), isAscending);
   }

   @Override
   public void setPropertyPath(PropertyPath propertyPath) {
      if (status == Status.DEFINING_SELECT) {
         if (propertyPath.getNodes().size() == 1 && propertyPath.getNodes().get(0).isAlias()) {
            projections.add("__HSearch_This"); //todo [anistor] this is a leftover from hsearch ????
         } else {
            projections.add(propertyPath.asStringPathWithoutAlias());
         }
      } else {
         this.propertyPath = propertyPath;
      }
   }

   @Override
   public void registerPersisterSpace(Tree entityName, Tree alias) {
      super.registerPersisterSpace(entityName, alias);

      targetEntityMetadata = propertyHelper.getEntityMetadata(targetTypeName);
   }

   @Override
   public FilterParsingResult<TypeMetadata> getResult() {
      return new FilterParsingResult<TypeMetadata>(builder.build(), targetTypeName, targetEntityMetadata, projections, null /*comparator*/);
   }
}
