/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2013 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2013 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.dao.jpa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.hibernate.FetchMode;
import org.hibernate.criterion.Criterion;
import org.opennms.core.criteria.AbstractCriteriaVisitor;
import org.opennms.core.criteria.Alias;
import org.opennms.core.criteria.Criteria;
import org.opennms.core.criteria.Fetch;
import org.opennms.core.criteria.Order;
import org.opennms.core.criteria.Order.OrderVisitor;
import org.opennms.core.criteria.restrictions.AllRestriction;
import org.opennms.core.criteria.restrictions.AnyRestriction;
import org.opennms.core.criteria.restrictions.BaseRestrictionVisitor;
import org.opennms.core.criteria.restrictions.BetweenRestriction;
import org.opennms.core.criteria.restrictions.EqRestriction;
import org.opennms.core.criteria.restrictions.GeRestriction;
import org.opennms.core.criteria.restrictions.GtRestriction;
import org.opennms.core.criteria.restrictions.IlikeRestriction;
import org.opennms.core.criteria.restrictions.InRestriction;
import org.opennms.core.criteria.restrictions.IplikeRestriction;
import org.opennms.core.criteria.restrictions.LeRestriction;
import org.opennms.core.criteria.restrictions.LikeRestriction;
import org.opennms.core.criteria.restrictions.LtRestriction;
import org.opennms.core.criteria.restrictions.NeRestriction;
import org.opennms.core.criteria.restrictions.NotNullRestriction;
import org.opennms.core.criteria.restrictions.NotRestriction;
import org.opennms.core.criteria.restrictions.NullRestriction;
import org.opennms.core.criteria.restrictions.Restriction;
import org.opennms.core.criteria.restrictions.RestrictionVisitor;
import org.opennms.core.criteria.restrictions.SqlRestriction;
import org.opennms.netmgt.dao.CriteriaConverter;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.*;

public class JPACriteriaConverter implements CriteriaConverter<CriteriaQuery> {

    private final EntityManager entityManager;

    public JPACriteriaConverter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public CriteriaQuery convert(final Criteria criteria) {
        final JPACriteriaVisitor visitor = new JPACriteriaVisitor(entityManager);
        criteria.visit(visitor);
        return visitor.getCriteriaQuery();
    }

    @Override
    public CriteriaQuery convertForCount(final Criteria criteria) {
        final JPACriteriaVisitor visitor = new CountJPACriteriaVisitor(entityManager);
        criteria.visit(visitor);
        return visitor.getCriteriaQuery();
    }

    private static class CountJPACriteriaVisitor extends JPACriteriaVisitor {

        private CountJPACriteriaVisitor(EntityManager em) {
            super(em);
        }

        @Override
        public void visitOrder(final Order order) {
            // skip order-by when converting for count
        }
    }

    private static class JPACriteriaVisitor extends AbstractCriteriaVisitor {
        private List<javax.persistence.criteria.Order> m_orders = new ArrayList<javax.persistence.criteria.Order>();

        private List<javax.persistence.criteria.Predicate> m_criterions = new ArrayList<javax.persistence.criteria.Predicate>();

        private boolean m_distinct = false;

        private Integer m_limit;

        private Integer m_offset;

        private final ConverterContext converterContext;

        private JPACriteriaVisitor(EntityManager entityManager) {
            converterContext = new ConverterContext();
            converterContext.setEntityManager(entityManager);
        }

        public Query getCriteriaQuery() {
            final CriteriaQuery criteriaQuery = fillCriteriaQuery();
            final TypedQuery query = converterContext.getEntityManager().createQuery(criteriaQuery);
            if (m_limit != null) query.setMaxResults(m_limit);
            if (m_offset != null) query.setFirstResult(m_offset);
            return query;
        }

        private CriteriaQuery fillCriteriaQuery() {
            if (converterContext.getCriteriaQuery() == null) {
                throw new IllegalStateException("Unable to determine Class<?> of this criteria!");
            }
            converterContext.getCriteriaQuery().where(m_criterions.toArray(new javax.persistence.criteria.Predicate[m_criterions.size()]));

            if (m_distinct) {
                m_criteria.setProjection(Projections.distinct(Projections.id()));

                final CriteriaQuery newCriteria = createCriteriaQuery(m_class);
                newCriteria.add(Subqueries.propertyIn("id", m_criteria));

                m_criteria = newCriteria;
            }
            converterContext.getCriteriaQuery().orderBy(m_orders);
            return converterContext.getCriteriaQuery();
        }

        @Override
        public void visitClass(final Class<?> clazz) {
            // Initialize JPA stuff
            CriteriaBuilder criteriaBuilder = converterContext.getEntityManager().getCriteriaBuilder();
            CriteriaQuery criteriaQuery = criteriaBuilder.createQuery(clazz);
            Root<?> root = criteriaQuery.from(clazz);

            // set for later use
            converterContext.setCriteriaBuilder(criteriaBuilder);
            converterContext.setCriteriaQuery(criteriaQuery);
            converterContext.setEntityClass(clazz);
            converterContext.setRoot(root);
        }

        @Override
        public void visitOrder(final Order order) {
            final JPAOrderVisitor visitor = new JPAOrderVisitor(converterContext);
            order.visit(visitor);
            // we hold onto these later because they need to be applied after
            // distinct projection
            m_orders.add(visitor.getOrder());
        }

        @Override
        public void visitAlias(final Alias alias) {
            int aliasType = 0;
            switch (alias.getType()) {
            case FULL_JOIN:
                aliasType = org.hibernate.Criteria.FULL_JOIN;
                break;
            case LEFT_JOIN:
                aliasType = org.hibernate.Criteria.LEFT_JOIN;
                break;
            case INNER_JOIN:
                aliasType = org.hibernate.Criteria.INNER_JOIN;
                break;
            default:
                aliasType = org.hibernate.Criteria.INNER_JOIN;
                break;
            }
            if (alias.hasJoinCondition()) { // an additional condition for the join
                final JPARestrictionVisitor visitor = new JPARestrictionVisitor(converterContext);
                alias.getJoinCondition().visit(visitor);
                m_criteria.createAlias(alias.getAssociationPath(), alias.getAlias(), aliasType, visitor.getPredicates().get(0));
            } else { // no additional condition for the join

                m_criteria.createAlias(alias.getAssociationPath(), alias.getAlias(), aliasType);
            }
        }

        @Override
        public void visitFetch(final Fetch fetch) {
            switch (fetch.getFetchType()) {
            case DEFAULT:
                m_criteria.setFetchMode(fetch.getAttribute(), FetchMode.DEFAULT);
                break;
            case EAGER:
                m_criteria.setFetchMode(fetch.getAttribute(), FetchMode.JOIN);
                break;
            case LAZY:
                m_criteria.setFetchMode(fetch.getAttribute(), FetchMode.SELECT);
                break;
            default:
                m_criteria.setFetchMode(fetch.getAttribute(), FetchMode.DEFAULT);
                break;
            }
        }

        @Override
        public void visitRestriction(final Restriction restriction) {
            final JPARestrictionVisitor visitor = new JPARestrictionVisitor();
            restriction.visit(visitor);
            m_criterions.addAll(visitor.getPredicates());
        }

        @Override
        public void visitDistinct(final boolean distinct) {
            m_distinct = distinct;
        }

        @Override
        public void visitLimit(final Integer limit) {
            m_limit = limit;
        }

        @Override
        public void visitOffset(final Integer offset) {
            m_offset = offset;
        }

    }

    public static final class JPAOrderVisitor implements OrderVisitor {
        private final ConverterContext context;

        private String m_attribute;

        private boolean m_ascending = true;

        public JPAOrderVisitor(ConverterContext context) {
            this.context = context;
        }

        @Override
        public void visitAttribute(final String attribute) {
            m_attribute = attribute;
        }

        @Override
        public void visitAscending(final boolean ascending) {
            m_ascending = ascending;
        }

        public javax.persistence.criteria.Order getOrder() {
            if (m_ascending) {
                return context.getCriteriaBuilder().asc(context.getRoot().get(m_attribute));
            } else {
                return context.getCriteriaBuilder().desc(context.getRoot().get(m_attribute));
            }
        }
    }

    private static final class JPARestrictionVisitor extends BaseRestrictionVisitor implements RestrictionVisitor {

        private final ConverterContext context;

        private final List<Predicate> predicateList = new ArrayList<Predicate>();

        private JPARestrictionVisitor(ConverterContext context) {
            this.context = context;
        }

        @Override
        public void visitNull(final NullRestriction restriction) {
            predicateList.add(
                    context.getCriteriaBuilder()
                            .isNull(context.getRoot().get(restriction.getAttribute())));
        }

        @Override
        public void visitNotNull(final NotNullRestriction restriction) {
            predicateList.add(
                    context.getCriteriaBuilder()
                            .isNotNull(context.getRoot().get(restriction.getAttribute())));
        }

        @Override
        public void visitEq(final EqRestriction restriction) {
            predicateList.add(
                    context.getCriteriaBuilder()
                            .equal(context.getRoot().get(restriction.getAttribute()), restriction.getValue()));
        }

        @Override
        public void visitNe(final NeRestriction restriction) {
            predicateList.add(
                    context.getCriteriaBuilder()
                            .notEqual(context.getRoot().get(restriction.getAttribute()), restriction.getValue()));
        }

        @Override
        public void visitGt(final GtRestriction restriction) {
            // TODO MVR check if cast works
            predicateList.add(
                    context.getCriteriaBuilder()
                            .gt((Path<Number>)context.getRoot().get(restriction.getAttribute()), restriction.getValue()));
        }

        @Override
        public void visitGe(final GeRestriction restriction) {
            // TODO MVR check if cast works
            predicateList.add(
                    context.getCriteriaBuilder()
                            .ge((Path<Number>)context.getRoot().get(restriction.getAttribute()), restriction.getValue()));
        }

        @Override
        public void visitLt(final LtRestriction restriction) {
            // TODO MVR check if cast works
            predicateList.add(
                    context.getCriteriaBuilder()
                            .lt((Path<Number>)context.getRoot().get(restriction.getAttribute()), restriction.getValue()));
        }

        @Override
        public void visitLe(final LeRestriction restriction) {
            // TODO MVR check if cast works
            predicateList.add(
                    context.getCriteriaBuilder()
                            .le((Path<Number>)context.getRoot().get(restriction.getAttribute()), restriction.getValue()));
        }

        @Override
        public void visitLike(final LikeRestriction restriction) {
            // TODO MVR check if cast works
            predicateList.add(
                    context.getCriteriaBuilder()
                            .like((Path<String>)context.getRoot().get(restriction.getAttribute()), restriction.getValue()));
        }

        @Override
        public void visitIlike(final IlikeRestriction restriction) {
            final CriteriaBuilder builder = context.getCriteriaBuilder();
            final Root<?> root = context.getRoot();
            builder.like(
                    builder.lower(
                            (Path<String>)root.get(restriction.getAttribute())),
                    ((String)restriction.getValue()).toLowerCase()); // TODO MVR maybe we have to add "%" around
        }

        @Override
        public void visitBetween(final BetweenRestriction restriction) {
            predicateList.add(
                    context.getCriteriaBuilder()
                            .between((Path<Comparable>)context.getRoot().get(restriction.getAttribute()), restriction.getBegin(), restriction.getEnd()));
        }

        @Override
        public void visitSql(final SqlRestriction restriction) {
            m_criterions.add(org.hibernate.criterion.Restrictions.sqlRestriction(restriction.getAttribute()));
        }

        @Override
        public void visitIplike(final IplikeRestriction restriction) {
            m_criterions.add(org.hibernate.criterion.Restrictions.sqlRestriction("iplike({alias}.ipAddr, ?)", (String) restriction.getValue(), STRING_TYPE));
        }

        @Override
        public void visitIn(final InRestriction restriction) {
            if (restriction.getValues() == null || restriction.getValues().size() == 0) {
                m_criterions.add(org.hibernate.criterion.Restrictions.sqlRestriction("0"));
            } else {
                m_criterions.add(org.hibernate.criterion.Restrictions.in(restriction.getAttribute(), restriction.getValues()));
            }
        }

        @Override
        public void visitAllComplete(final AllRestriction restriction) {
            final int restrictionSize = restriction.getRestrictions().size();
            final int criterionSize = m_criterions.size();
            if (criterionSize < restrictionSize) {
                throw new IllegalStateException("AllRestriction with " + restrictionSize + " entries encountered, but we only have " + criterionSize + " criterions!");
            }
            final List<Criterion> criterions = m_criterions.subList(criterionSize - restrictionSize, criterionSize);
            final Junction j = org.hibernate.criterion.Restrictions.conjunction();
            for (final Criterion crit : criterions) {
                j.add(crit);
            }
            criterions.clear();
            m_criterions.add(j);
        }

        @Override
        public void visitAnyComplete(final AnyRestriction restriction) {
            final int restrictionSize = restriction.getRestrictions().size();
            final int criterionSize = m_criterions.size();
            if (criterionSize < restrictionSize) {
                throw new IllegalStateException("AllRestriction with " + restrictionSize + " entries encountered, but we only have " + criterionSize + " criterions!");
            }
            final List<Criterion> criterions = m_criterions.subList(criterionSize - restrictionSize, criterionSize);
            final Junction j = org.hibernate.criterion.Restrictions.disjunction();
            for (final Criterion crit : criterions) {
                j.add(crit);
            }
            criterions.clear();
            m_criterions.add(j);
        }

        @Override
        public void visitNotComplete(final NotRestriction restriction) {
            if (m_criterions.size() == 0) {
                throw new IllegalStateException("NotRestriction called, but no criterions exist to negate!");
            }
            final Criterion criterion = m_criterions.remove(m_criterions.size() - 1);
            m_criterions.add(org.hibernate.criterion.Restrictions.not(criterion));
        }

        public List<? extends Predicate> getPredicates() {
            return predicateList;
        }
    }

    /**
     * Is used to determine the query using the Criteria API.
     * To do so the EntityManager, CriteriaBuilder, Root and entityClass is needed
     * during the creation of the CriteriaQuery.
     */
    private static class ConverterContext {
        private EntityManager entityManager;
        private CriteriaBuilder criteriaBuilder;
        private CriteriaQuery criteriaQuery;
        private Root<?> root;
        private Class<?> entityClass;

        private ConverterContext() {}

        private EntityManager getEntityManager() {
            return entityManager;
        }

        private void setEntityManager(EntityManager entityManager) {
            this.entityManager = entityManager;
        }

        private CriteriaBuilder getCriteriaBuilder() {
            return criteriaBuilder;
        }

        private void setCriteriaBuilder(CriteriaBuilder criteriaBuilder) {
            this.criteriaBuilder = criteriaBuilder;
        }

        private CriteriaQuery getCriteriaQuery() {
            return criteriaQuery;
        }

        private void setCriteriaQuery(CriteriaQuery criteriaQuery) {
            this.criteriaQuery = criteriaQuery;
        }

        private Root<?> getRoot() {
            return root;
        }

        private void setRoot(Root<?> root) {
            this.root = root;
        }

        private void setEntityClass(Class<?> entityClass) {
            this.entityClass = entityClass;
        }

        private Class<?> getEntityClass() {
            return entityClass;
        }
    }
}